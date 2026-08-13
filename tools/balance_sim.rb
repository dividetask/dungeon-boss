#!/usr/bin/env ruby
# frozen_string_literal: true

# Dungeon Boss — standalone balance simulator.
#
# A faithful, dependency-free re-implementation of the Android crawl math
# (PartyCrawlResolver + HeroAbility), driven by the canonical card data in
# data/cards/. Its job is to answer balance questions empirically instead of by
# intuition: how much damage a room deals to a party, whether trap-heavy or
# monster-heavy dungeons kill more heroes, how a boss's per-point bonus scales,
# and so on.
#
# The crawl has NO randomness, so every matchup is resolved exactly once and the
# numbers are reproducible. Run: `ruby tools/balance_sim.rb`.
#
# This is a design/analysis tool, not shipped code. It mirrors the Android
# engine; if the engine's rules change, update this to match.

require "yaml"

DATA_DIR = File.expand_path("../data/cards", __dir__)

# ---------------------------------------------------------------------------
# Card loading (canonical data/cards/*.yaml)
# ---------------------------------------------------------------------------
module Cards
  def self.load
    bosses = YAML.load_file(File.join(DATA_DIR, "bosses.yaml"))["bosses"]
    rooms_doc = YAML.load_file(File.join(DATA_DIR, "rooms.yaml"))
    rooms = (rooms_doc["rooms"] || []) + (rooms_doc["advanced_rooms"] || [])
    heroes = YAML.load_file(File.join(DATA_DIR, "heroes.yaml"))["heroes"]
    {
      bosses: bosses.to_h { |b| [b["id"], b] },
      rooms: rooms.to_h { |r| [r["id"], r] },
      heroes: heroes.to_h { |h| [h["id"], h] }
    }
  end
end

# ---------------------------------------------------------------------------
# Encounter: a room or a boss on the board. Carries a mutable level (growth /
# upgrades). Distinct instances are distinct by object identity (like Kotlin).
# ---------------------------------------------------------------------------
class Encounter
  attr_accessor :level
  attr_reader :def_

  def initialize(defn, boss: false, level: 0)
    @def_ = defn
    @boss = boss
    @level = level
  end

  def boss? = @boss
  def id = @def_["id"]
  def name = @def_["name"]
  def type = @def_["type"]
  def tags = @def_["tags"] || []
  def bait = @def_["bait"] || {}
  def bait_count(b) = (bait[b.to_s] || 0)
  def trap? = type.to_s.downcase.include?("trap")
  def creature? = (t = type.to_s.downcase; t.include?("monster") || t.include?("creature"))

  def lead_base = @boss ? (@def_["lead_damage"] || 0) : (@def_["lead_damage"] || 0)
  def lead = lead_base + ((@def_["lead_damage_increment"] || 0) * level).floor
  def all  = (@def_["damage_all"] || 0) + ((@def_["damage_all_increment"] || 0) * level).floor
  def rear = (@def_["damage_rear"] || 0) + ((@def_["damage_rear_increment"] || 0) * level).floor

  def damage_filter = @def_["damage_filter"]
  def room_resist = @def_.key?("room_resist") ? @def_["room_resist"] : nil
  def poison_damage = @def_["poison_damage"] || 0
  def poison_persists? = @def_["poison_persists"] ? true : false
  def poison_ticks = @def_["poison_ticks"] || 1
  def grows_on_death? = @def_["grows_on_death"] ? true : false
  def room_aura = @def_["room_aura"]
  def effect = @def_["effect"] || {}
end

# ---------------------------------------------------------------------------
# Hero: fully data-driven, carries a level (fixed for a given crawl here).
# ---------------------------------------------------------------------------
class Hero
  attr_reader :def_, :level
  def initialize(defn, level: 1)
    @def_ = defn
    @level = level
  end

  def id = @def_["id"]
  def name = @def_["name"]
  def preferred_bait = @def_["preferred_bait"]
  def self_mult = (@def_["self_damage_multiplier"] || 1).to_f
  def bait_filter = @def_["damage_bait_filter"]
  def type_filter = @def_["damage_room_type_filter"]

  # Stats use (level - 1), so a level-1 hero has its base stats (mirrors the game).
  def bonus_levels = [@level - 1, 0].max
  def max_hp = (@def_["starting_hp"]) + ((@def_["hp_level_increment"] || 0) * bonus_levels).floor
  def party_reduction
    (@def_["party_damage_reduction"] || 0) +
      ((@def_["party_damage_reduction_level_increment"] || 0) * bonus_levels).floor
  end
end

# ---------------------------------------------------------------------------
# Effect selectors / boss & room bonuses (mirrors Effects.kt / BossEffect.kt)
# ---------------------------------------------------------------------------
module Effects
  def self.matches?(enc, m)
    m = m || {}
    if (t = m["tag"]) then return false unless enc.tags.map(&:to_s).include?(t.to_s) end
    if (ty = m["type"])
      ok = (ty == "trap") ? enc.trap? : (%w[creature monster].include?(ty) ? enc.creature? : false)
      return false unless ok
    end
    if (b = m["bait"]) then return false unless enc.bait_count(b) > 0 end
    true
  end

  def self.aura_bonus(source_room, target, points)
    a = source_room.room_aura
    return 0 unless a
    matches?(target, a["match"]) ? ((a["flat"] || 0) + (a["amount"] || 0) + (a["per_point"] || 0) * points) : 0
  end
end

class BossEffect
  def initialize(effect)
    @effect = effect || {}
    @self_pp = @effect.key?("self_damage_per_point") ? @effect["self_damage_per_point"].to_i : 1
    @room_bonuses = (@effect["room_bonuses"] || [])
  end

  def self_bonus(points) = @self_pp * points
  def unreducible? = (@effect["unreducible"] ? true : false)

  def room_bonus(room, points)
    @room_bonuses.sum do |b|
      Effects.matches?(room, b["match"]) ? ((b["flat"] || 0) + (b["per_point"] || 0) * points) : 0
    end
  end
end

# ---------------------------------------------------------------------------
# Dungeon
# ---------------------------------------------------------------------------
class Dungeon
  attr_reader :rooms, :boss
  def initialize(rooms:, boss: nil)
    @rooms = rooms
    @boss = boss
  end

  def encounters = @boss ? (@rooms + [@boss]) : @rooms.dup
end

# ---------------------------------------------------------------------------
# Crawl resolver — faithful port of PartyCrawlResolver + HeroAbility.
# Resist modes: :normal, :no_halve, :no_reduce.
# ---------------------------------------------------------------------------
class CrawlResolver
  Step = Struct.new(:room_index, :encounter, :hero, :damage, :hp_after, :died, keyword_init: true)
  Result = Struct.new(:deaths, :survivors, :dead, :damage_to, :log, keyword_init: true)

  def self.resolve(heroes, dungeon, boss_bonus: 0)
    new(heroes, dungeon, boss_bonus).resolve
  end

  def initialize(heroes, dungeon, boss_bonus)
    @dungeon = dungeon
    @boss_bonus = boss_bonus
    @boss_effect = dungeon.boss ? BossEffect.new(dungeon.boss.effect) : nil
    @participants = heroes.dup
    @alive = heroes.dup
    @dead = []
    @health = {}
    @poison = Hash.new(0)
    @delayed = Hash.new(0)
    @damage_to = Hash.new(0) # hero -> total damage taken (for threat metrics)
    @log = []                # ordered Step records (for the trace mode)
    @room_index = 0
    heroes.each { |h| @health[h] = h.max_hp }
  end

  def resolve
    @dungeon.encounters.each_with_index do |enc, idx|
      break if @alive.empty?
      @room_index = idx
      @deaths_here = 0
      poison_tick(enc)
      delayed_tick(enc) unless @alive.empty?
      apply_damage(enc) unless @alive.empty?
      grow(enc)
    end
    Result.new(deaths: @dead.size, survivors: @alive.dup, dead: @dead.dup,
               damage_to: @damage_to, log: @log)
  end

  private

  def pick_highest
    best = nil
    @alive.each do |h|
      if best.nil? || cmp(h, best) > 0 then best = h end
    end
    best
  end

  def pick_lowest
    best = nil
    @alive.each do |h|
      if best.nil? || cmp(h, best) < 0 then best = h end
    end
    best
  end

  # compare by (current health, max hp); ties -> 0 (caller keeps earliest).
  def cmp(a, b)
    return @health[a] <=> @health[b] unless @health[a] == @health[b]
    a.max_hp <=> b.max_hp
  end

  def poison_tick(enc)
    ticks = [1, enc.poison_ticks].max
    @alive.dup.each do |h|
      amt = @poison[h]
      next unless amt.positive?
      ticks.times { hit(h, amt, enc, :no_reduce) if @alive.include?(h) }
    end
  end

  def delayed_tick(enc)
    ticks = [1, enc.poison_ticks].max
    @alive.dup.each do |h|
      amt = @delayed[h]
      next unless amt.positive?
      ticks.times { hit(h, amt, enc, :no_reduce) if @alive.include?(h) }
      @delayed.delete(h)
    end
  end

  def apply_damage(enc)
    ch = channels(enc)
    resist = resist_mode(enc)
    if ch[:all] > 0
      f = enc.damage_filter
      targets = f ? @alive.select { |h| matches_filter?(h, f) } : @alive.dup
      targets.each { |h| apply_hit_with_poison(h, ch[:all], enc, resist) }
    end
    cascade(ch[:lead], enc, resist) { pick_highest } if ch[:lead] > 0
    cascade(ch[:rear], enc, resist) { pick_lowest } if ch[:rear] > 0
  end

  def cascade(amount, enc, resist)
    remaining = amount
    while remaining > 0 && !@alive.empty?
      target = yield
      before = @health[target]
      dealt = apply_hit_with_poison(target, remaining, enc, resist)
      break if dealt <= 0
      remaining = dealt > before ? dealt - before : 0
    end
  end

  def apply_hit_with_poison(hero, amount, enc, resist)
    dealt = hit(hero, amount, enc, resist)
    return 0 if dealt <= 0
    if @alive.include?(hero) && enc.poison_damage.positive?
      if enc.poison_persists?
        @poison[hero] += enc.poison_damage
      else
        @delayed[hero] += enc.poison_damage
      end
    end
    dealt
  end

  def channels(enc)
    lead = enc.lead
    all = enc.all
    rear = enc.rear
    # Mirror: lead damage = the toughest alive member's max HP.
    lead = (@alive.map(&:max_hp).max || 0) if enc.def_["lead_max_party_hp"]
    ext =
      if enc.boss?
        @boss_effect ? @boss_effect.self_bonus(@boss_bonus) : 0
      else
        (@boss_effect ? @boss_effect.room_bonus(enc, @boss_bonus) : 0) + aura_bonus(enc)
      end
    if lead > 0 || (all == 0 && rear == 0)
      lead += ext
    elsif all > 0
      all += ext
    else
      rear += ext
    end
    { lead: lead, all: all, rear: rear }
  end

  def aura_bonus(enc)
    @dungeon.rooms.sum { |r| r.equal?(enc) ? 0 : Effects.aura_bonus(r, enc, @boss_bonus) }
  end

  def resist_mode(enc)
    return :no_reduce if @boss_effect && enc.boss? && @boss_effect.unreducible?
    case enc.room_resist
    when true then :no_reduce
    when false then :no_halve
    else :normal
    end
  end

  def matches_filter?(hero, filter)
    hero.name.casecmp?(filter) || hero.id.casecmp?("hero_#{filter}")
  end

  def grow(enc)
    return unless enc.grows_on_death? && @deaths_here.positive?
    enc.level += @deaths_here
  end

  def hit(hero, amount, enc, resist)
    return 0 if amount <= 0
    dmg = damage_taken(hero, enc, amount, resist)
    @health[hero] -= dmg
    @damage_to[hero] += dmg
    died = @health[hero] <= 0
    @log << Step.new(room_index: @room_index, encounter: enc, hero: hero,
                     damage: dmg, hp_after: @health[hero], died: died)
    if died
      idx = @alive.index { |h| h.equal?(hero) }
      @alive.delete_at(idx) if idx
      @dead << hero
      @deaths_here += 1
    end
    dmg
  end

  def damage_taken(target, enc, base, resist)
    return [base, 0].max if resist == :no_reduce
    d = base
    @alive.each { |m| d = [d - party_reduction(m, enc), 0].max }
    d = (d * target.self_mult).ceil if resist == :normal
    [d, 0].max
  end

  def party_reduction(member, enc)
    filter_matches?(member, enc) ? member.party_reduction : 0
  end

  def filter_matches?(member, enc)
    if (bf = member.bait_filter)
      return false if enc.bait_count(bf) == 0
    end
    if (tf = member.type_filter)
      ok = case tf.to_s.downcase
           when "trap" then enc.trap?
           when "creature", "monster" then enc.creature?
           else enc.type.to_s.downcase.include?(tf.to_s.downcase)
           end
      return false unless ok
    end
    true
  end
end

# ===========================================================================
# Experiments / report
# ===========================================================================
DB = Cards.load

def hero(id, level: 1) = Hero.new(DB[:heroes].fetch(id), level: level)
def room(id, level: 0) = Encounter.new(DB[:rooms].fetch(id), level: level)
def boss(id) = Encounter.new(DB[:bosses].fetch(id), boss: true)

def party(*ids, level: 1) = ids.map { |i| hero(i, level: level) }

ALL_CLASSES = %w[hero_barbarian hero_rogue hero_cleric hero_mage].freeze

def report
  puts "=" * 78
  puts "DUNGEON BOSS — balance simulator (deterministic; mirrors the Android engine)"
  puts "Loaded #{DB[:bosses].size} bosses, #{DB[:rooms].size} rooms, #{DB[:heroes].size} heroes"
  puts "=" * 78

  room_threat_table
  archetype_experiment
  boss_scaling
  full_dungeon_experiment
  boss_tournament
  trace_demo
end

# --- 1) Per-room threat: damage to a standard 4-class party, one encounter ---
def room_threat_table
  puts "\n## 1. Room threat — one encounter vs a full 4-class party (each level 0)"
  puts "   'to party' = total damage dealt across all members; 'kills' = deaths."
  puts "   Party auras active: Rogue -2 all traps, Mage -4 arcane, Cleric -4 undead.\n\n"
  rows = DB[:rooms].values.map do |rdef|
    d = Dungeon.new(rooms: [Encounter.new(rdef)])
    r = CrawlResolver.resolve(party(*ALL_CLASSES), d)
    total = r.damage_to.values.sum
    [rdef["name"], rdef["type"], primary_channel(rdef), total, r.deaths]
  end
  rows.sort_by! { |x| -x[3] }
  printf("   %-22s %-8s %-6s %8s %6s\n", "room", "type", "chan", "to party", "kills")
  rows.each { |n, t, c, tot, k| printf("   %-22s %-8s %-6s %8d %6d\n", n, t, c, tot, k) }
end

def primary_channel(rdef)
  return "all"  if (rdef["damage_all"] || 0) > 0 && (rdef["lead_damage"] || 0) == 0
  return "rear" if (rdef["damage_rear"] || 0) > 0 && (rdef["lead_damage"] || 0) == 0 && (rdef["damage_all"] || 0) == 0
  "lead"
end

# --- 2) Trap-heavy vs monster-heavy dungeons vs several parties -------------
TRAP_DUNGEON    = %w[room_fireball room_pit room_poison_gas room_cursed_ring room_power_word].freeze
MONSTER_DUNGEON = %w[room_goblins room_skeletons room_champion room_succubus room_mimic].freeze
MIXED_DUNGEON   = %w[room_fireball room_skeletons room_poison_gas room_champion room_pit].freeze

PARTIES = {
  "solo Barbarian"      => %w[hero_barbarian],
  "solo Mage"           => %w[hero_mage],
  "4x Barbarian (no reducers)" => %w[hero_barbarian hero_barbarian hero_barbarian hero_barbarian],
  "1 of each class"     => ALL_CLASSES.dup
}.freeze

DUNGEONS = {
  "trap-heavy"    => TRAP_DUNGEON,
  "monster-heavy" => MONSTER_DUNGEON,
  "mixed"         => MIXED_DUNGEON
}.freeze

def archetype_experiment
  [1, 5].each do |lvl|
    puts "\n## 2. Trap-heavy vs monster-heavy — rooms only, no boss (hero level #{lvl})"
    puts "   Cell = deaths / party size. Higher deaths = stronger dungeon.\n\n"
    printf("   %-28s %-14s %-14s %-14s\n", "party", *DUNGEONS.keys)
    PARTIES.each do |pname, pids|
      cells = DUNGEONS.map do |_, rids|
        d = Dungeon.new(rooms: rids.map { |i| room(i) })
        r = CrawlResolver.resolve(pids.map { |i| hero(i, level: lvl) }, d)
        "#{r.deaths}/#{pids.size}"
      end
      printf("   %-28s %-14s %-14s %-14s\n", pname, *cells)
    end
  end
end

# --- 3) Boss per-point scaling ---------------------------------------------
def boss_scaling
  puts "\n## 3. Boss final-hit damage to the lead hero by owner points"
  puts "   (raw boss damage before hero reductions; shows how each boss scales)\n\n"
  pts = [0, 2, 4, 6, 8]
  printf("   %-18s %s\n", "boss", pts.map { |p| format('%4dpt', p) }.join(" "))
  DB[:bosses].each do |id, bdef|
    be = BossEffect.new(bdef["effect"])
    vals = pts.map { |p| (bdef["lead_damage"] || 0) + be.self_bonus(p) }
    tag = be.unreducible? ? " [unreducible]" : ""
    printf("   %-18s %s%s\n", bdef["name"], vals.map { |v| format('%6d', v) }.join(" "), tag)
  end
end

# --- 4/5) Themed full dungeons: each boss in its ideal 5-room dungeon -------
# Synergy bosses get rooms their ability buffs; the three non-synergy bosses
# (Vampire, Malevolent Spirit, Medusa) get a generic strong "bruiser" dungeon,
# reflecting that they just pick good rooms rather than build around a theme.
GENERIC_BRUISERS = %w[room_champion room_succubus room_mimic adv_troll room_zombies].freeze
BOSS_DUNGEONS = {
  "boss_lich"             => %w[room_fireball room_power_word room_cursed_ring adv_maze adv_black_tentacles],
  "boss_necromancer"      => %w[room_skeletons room_zombies room_shade adv_shadow adv_wright],
  "boss_oni"              => %w[room_goblins room_champion adv_gladiator adv_troll adv_beast_tamer],
  "boss_goblin_chieftain" => %w[room_goblins room_champion adv_beast_tamer room_champion room_goblins],
  "boss_kobold_chieftain" => %w[room_fireball room_pit room_floor_spike room_poison_gas adv_antimagic_room],
  "boss_vampire"          => GENERIC_BRUISERS,
  "boss_malevolent_spirit"=> GENERIC_BRUISERS,
  "boss_medusa"           => GENERIC_BRUISERS
}.freeze

# Game arc: (hero level, owner points) — early / mid / late play.
ARC = [[1, 0], [3, 3], [5, 6]].freeze

# Fresh instances every call (growth mutates a room's level in place).
def themed_dungeon(bid)
  Dungeon.new(rooms: BOSS_DUNGEONS.fetch(bid).map { |r| room(r) }, boss: boss(bid))
end

def full_dungeon_experiment
  puts "\n## 4. Full themed dungeon (5 rooms + boss) vs a 4-class party — deaths/4"
  puts "   Synergy bosses in their element; non-synergy bosses get a generic dungeon."
  puts "   Columns are the game arc: hero level / owner points.\n\n"
  printf("   %-20s %s\n", "boss", ARC.map { |l, p| format('%-9s', "L#{l}/#{p}pt") }.join(" "))
  DB[:bosses].keys.each do |bid|
    cells = ARC.map do |lvl, pts|
      r = CrawlResolver.resolve(party(*ALL_CLASSES, level: lvl), themed_dungeon(bid), boss_bonus: pts)
      format('%-9s', "#{r.deaths}/4")
    end
    printf("   %-20s %s\n", DB[:bosses][bid]["name"], cells.join(" "))
  end
end

# Two defensive extremes so we see each boss's best and worst matchup:
#   balanced   = one of every class -> carries every reduction filter (Rogue -2
#                traps, Mage -4 arcane, Cleric -4 undead). Worst case for bosses
#                whose bait/rooms those filters counter.
#   no-reducer = 4 Barbarians -> no party auras at all (only self-halving).
TOURNAMENT_PARTIES = [
  ["balanced (all reducers)", ALL_CLASSES],
  ["no-reducer (4x Barb)", Array.new(4, "hero_barbarian")]
].freeze

def boss_tournament
  puts "\n## 5. Boss tournament — kills summed across the arc (ideal dungeon per boss)"
  puts "   Two parties: a balanced 4-class party (all reducers) vs 4 Barbarians"
  puts "   (no reducers). Each column is kills / 12 (3 arc stages x max 4). Sorted"
  puts "   by the balanced column.\n\n"
  rows = DB[:bosses].keys.map do |bid|
    cols = TOURNAMENT_PARTIES.map do |_, pids|
      ARC.sum do |lvl, pts|
        CrawlResolver.resolve(pids.map { |i| hero(i, level: lvl) }, themed_dungeon(bid), boss_bonus: pts).deaths
      end
    end
    [DB[:bosses][bid]["name"], cols]
  end
  rows.sort_by! { |_, cols| -cols[0] }
  printf("   %-4s%-20s %-20s %-18s\n", "", "boss", *TOURNAMENT_PARTIES.map { |n, _| n })
  rows.each_with_index do |(name, cols), i|
    printf("   %2d. %-20s %-20s %-18s\n", i + 1, name,
           "#{cols[0]}/12", "#{cols[1]}/12")
  end
end

# --- 6) Damage trace: inspect any single matchup step by step ---------------
# Reusable: trace_crawl(party(...), themed_dungeon("boss_x"), boss_bonus: N)
def trace_crawl(heroes, dungeon, boss_bonus: 0)
  res = CrawlResolver.resolve(heroes, dungeon, boss_bonus: boss_bonus)
  puts "   party: #{heroes.map { |h| "#{h.name}(#{h.max_hp}hp)" }.join(', ')}   boss points: #{boss_bonus}"
  cur = nil
  res.log.each do |s|
    if s.room_index != cur
      cur = s.room_index
      label = s.encounter.boss? ? "BOSS: #{s.encounter.name}" : "slot #{s.room_index}: #{s.encounter.name}"
      puts "   -- #{label} --"
    end
    printf("      %-10s -%-2d -> %3d hp%s\n", s.hero.name, s.damage, s.hp_after, s.died ? "   *** DIES ***" : "")
  end
  puts "   => #{res.deaths} died, #{res.survivors.size} survived" \
       "#{res.survivors.empty? ? '' : " (#{res.survivors.map(&:name).join(', ')})"}"
end

def trace_demo
  puts "\n## 6. Damage trace (example) — 4-class party (L1) vs Oni glory-monster dungeon, 0 pts"
  puts "   Every hit shown, incl. cascades. Use trace_crawl(...) for any matchup.\n\n"
  trace_crawl(party(*ALL_CLASSES, level: 1), themed_dungeon("boss_oni"), boss_bonus: 0)
end

report if __FILE__ == $PROGRAM_NAME
