# frozen_string_literal: true

require_relative "hero_ability"
require_relative "room_effect"
require_relative "boss_effect"
require_relative "crawl_modifiers"

# Runs a whole party through a dungeon, applying room effects. At each room, in
# order: a poison tick, the room's party-wide hits (Antimagic/Zealots,
# unreducible), then a single-target hit on the highest-health hero (reducible).
# Single-target damage includes the room's grow bonus, dungeon auras (Trap
# Makers / Hobgoblin Beastmaster), and the boss's points bonus. A hero damaged by a Poison
# Gas room is poisoned (+1 unreducible per later room). A hero that hits 0 dies;
# grow-on-death rooms gain +1 permanently per death there.
# Stateless entry point; uses an instance to carry crawl state.
class PartyCrawlResolver
  # participants: heroes who started, in board order
  # log: ordered [{ encounter:, hero:, damage:, health_after:, died:, room_index: }]
  # draws: {"ability"|"room" => count} the owner draws from death-triggered rooms
  Result = Struct.new(:participants, :log, :deaths, :dead_heroes, :survivors, :draws,
                      keyword_init: true)

  def self.resolve(party, dungeon, boss_bonus: 0, modifiers: CrawlModifiers.new)
    new(party, dungeon, boss_bonus, modifiers).resolve
  end

  def initialize(party, dungeon, boss_bonus, modifiers)
    @dungeon = dungeon
    @boss_bonus = boss_bonus
    @mods = modifiers
    @participants = party.heroes.dup
    @health = @participants.to_h { |hero| [hero, hero.health] }
    @alive = @participants.dup
    @dead = []
    @poison = Hash.new(0) # hero => poison stacks
    @delayed = Hash.new(0) # hero => unreducible damage to take in the NEXT room
    @draws = Hash.new(0)   # deck name ("ability"/"room") => cards the owner draws
    @log = []
  end

  def resolve
    @dungeon.encounters.each_with_index do |encounter, index|
      break if @alive.empty?
      break if @mods.retreats_at?(index) # Retreat: the party turns back here

      @room_index = index
      @deaths_here = 0

      poison_tick(encounter)
      delayed_tick(encounter) unless @alive.empty?
      apply_party_hits(encounter) unless @alive.empty?
      apply_single_target(encounter) unless @alive.empty?
      grow(encounter)
      record_death_draws(encounter)
    end

    Result.new(participants: @participants, log: @log, deaths: @dead.size,
               dead_heroes: @dead, survivors: @alive, draws: @draws)
  end

  private

  # Most current health, then most max health, then earliest board order.
  def pick_target
    @alive.max_by { |hero| [@health[hero], hero.health] }
  end

  def poison_tick(encounter)
    @alive.dup.each do |hero|
      stacks = @poison[hero]
      hit(hero, stacks, encounter, reducible: false) if stacks.positive?
    end
  end

  # One-shot delayed damage (e.g. Poisoned Spikes) lands as the hero enters the
  # next room, then clears.
  def delayed_tick(encounter)
    @alive.dup.each do |hero|
      amount = @delayed[hero]
      next unless amount.positive?

      hit(hero, amount, encounter, reducible: false)
      @delayed.delete(hero)
    end
  end

  def apply_party_hits(encounter)
    RoomEffect.for(encounter).party_hits(@alive.dup, encounter).each do |hero, amount|
      next unless @alive.include?(hero)

      hit(hero, amount, encounter, reducible: false)
    end
  end

  def apply_single_target(encounter)
    base = effective_base(encounter)
    return unless base.positive?

    effect = RoomEffect.for(encounter)
    # A per-crawl unreducible (Expose Weakness / Troll) OR the room's own
    # unreducible effect (Champion's Arena) makes this hit unreducible.
    reducible = @mods.reducible?(@room_index) && !effect.unreducible?
    target = pick_target
    dealt = hit(target, base, encounter, reducible: reducible)
    return unless dealt.positive?

    # A hero hit by a Poison Gas room is poisoned thereafter.
    @poison[target] += 1 if effect.poisons_on_hit?
    # A hero hit by a Poisoned Spikes room takes delayed damage in the next room.
    @delayed[target] += effect.next_room_damage if effect.next_room_damage.positive?
  end

  # When heroes die in a room with a draw-on-death effect, the owner draws.
  def record_death_draws(encounter)
    deck = RoomEffect.for(encounter).draws_on_death
    @draws[deck] += @deaths_here if deck && @deaths_here.positive?
  end

  def effective_base(encounter)
    return 0 if @mods.zero?(@room_index)

    base = @mods.set?(@room_index) ? @mods.set_value(@room_index) : encounter.damage
    boss_effect = BossEffect.for(@dungeon.boss)
    if encounter.equal?(@dungeon.boss)
      base += boss_effect.self_bonus(@boss_bonus) # boss's own per-point bonus
    else
      base += boss_effect.room_bonus(encounter, @boss_bonus) # boss aura on this room
    end
    base += aura_bonus(encounter)
    base + @mods.bonus(@room_index)
  end

  # Damage other rooms' auras grant to this room (never the granter itself).
  def aura_bonus(encounter)
    @dungeon.rooms.sum do |room|
      room.equal?(encounter) ? 0 : RoomEffect.for(room).aura_bonus(encounter, @boss_bonus)
    end
  end

  def grow(encounter)
    return unless RoomEffect.for(encounter).grows_on_death? && @deaths_here.positive?

    encounter.grow += @deaths_here
  end

  # Deal damage to a hero and record it; returns the actual damage dealt.
  def hit(hero, amount, encounter, reducible:)
    return 0 unless amount.positive?

    damage = reducible ? HeroAbility.damage_taken(hero, encounter, @alive, amount) : amount
    @health[hero] -= damage
    died = @health[hero] <= 0
    @log << { encounter: encounter, hero: hero, damage: damage,
              health_after: @health[hero], died: died, room_index: @room_index }
    if died
      @alive.delete(hero)
      @dead << hero
      @deaths_here += 1
    end
    damage
  end
end
