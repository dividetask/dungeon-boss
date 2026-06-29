# frozen_string_literal: true

require_relative "test_helper"

# Room effects are declarative data (see data/cards.yaml); these tests build the
# same effect specs inline and check RoomEffect interprets them via the resolver.
class RoomEffectTest < Minitest::Test
  GROW   = { "grows_on_death" => true }.freeze
  POISON = { "poisons_on_hit" => true }.freeze

  def buff(type)
    { "room_auras" => [{ "match" => { "type" => type }, "flat" => 2 }] }
  end

  def party_hit(preferred_bait, amount: 4)
    { "party_hits" => [{ "match" => { "preferred_bait" => preferred_bait }, "amount" => amount }] }
  end

  def room(id, type, dmg, effect: nil, bait: {})
    Room.new(id: id, name: id, type: type, damage: dmg, bait: bait, effect: effect,
             advanced: !effect.nil?)
  end

  def hero(id, bait, hp)
    Hero.new(id: id, name: id, health: hp, preferred_bait: bait)
  end

  # rooms listed left-to-right (entrance first)
  def dungeon(boss_dmg, rooms)
    d = Dungeon.new(Boss.new(id: "b", name: "Boss", damage: boss_dmg, bait: {}))
    rooms.reverse_each { |r| d.add_room_to_left(r) }
    d
  end

  def test_grow_on_death_permanently_increases_room_damage
    d = dungeon(0, [room("plague", "Creature", 4, effect: GROW)])
    PartyCrawlResolver.resolve(Party.new([hero("h", :glory, 4)]), d)

    assert_equal 1, d.rooms.first.grow # +1 after the hero died there
    assert_equal 5, d.rooms.first.damage # base 4 + grow 1
  end

  def test_buff_traps_adds_two_to_other_trap_rooms_only
    trap_maker = room("tm", "Creature", 2, effect: buff("trap"))
    trap = room("spikes", "Basic Trap", 2)
    d = dungeon(0, [trap_maker, trap])
    res = PartyCrawlResolver.resolve(Party.new([hero("tank", :glory, 100)]), d)

    by_room = res.log.group_by { |s| s[:encounter].id }
    assert_equal 2, by_room["tm"].first[:damage]      # the granter is unbuffed
    assert_equal 4, by_room["spikes"].first[:damage]  # 2 + 2 from the aura
  end

  def test_beast_tamer_buffs_basic_and_advanced_monster_rooms
    beast_tamer = room("bt", "Creature", 4, effect: buff("creature")) # advanced creature
    basic_monster = room("skeletons", "Basic Monster", 1)             # basic monster
    advanced_monster = room("succubus", "Creature", 3)                # advanced creature
    trap = room("spikes", "Basic Trap", 2)                            # not a monster
    d = dungeon(0, [beast_tamer, basic_monster, advanced_monster, trap])
    res = PartyCrawlResolver.resolve(Party.new([hero("tank", :glory, 100)]), d)

    by_room = res.log.group_by { |s| s[:encounter].id }
    assert_equal 1 + 2, by_room["skeletons"].first[:damage]    # basic monster IS buffed
    assert_equal 3 + 2, by_room["succubus"].first[:damage]     # advanced creature buffed
    assert_equal 2, by_room["spikes"].first[:damage]           # trap unaffected
    assert_equal 4, by_room["bt"].first[:damage]               # the granter is unbuffed
  end

  def test_antimagic_hits_only_mages_and_cannot_be_reduced
    d = dungeon(0, [room("anti", "Trap", 0, effect: party_hit("power"))])
    mage = hero("hero_mage", :power, 10) # real Mage id -> would normally reduce power
    warrior = hero("w", :glory, 10)
    res = PartyCrawlResolver.resolve(Party.new([mage, warrior]), d)

    hits = res.log.select { |s| s[:hero] == mage }
    assert_equal 1, hits.size
    assert_equal 4, hits.first[:damage] # full 4 despite the Mage's power reduction
    assert(res.log.none? { |s| s[:hero] == warrior })
  end

  def test_poison_gas_damages_one_hero_then_ticks_each_later_room
    d = dungeon(0, [room("gas", "Trap", 2, effect: POISON),
                    room("hall", "Trap", 0)])
    h = hero("h", :glory, 10)
    res = PartyCrawlResolver.resolve(Party.new([h]), d)

    # gas: -2 (and poisoned); hall: poison -1; boss: poison -1  => 10 - 4 = 6
    damages = res.log.map { |s| s[:damage] }
    assert_equal [2, 1, 1], damages
    assert_equal 6, res.log.last[:health_after]
  end

  def test_poison_gas_only_poisons_the_hero_it_damages
    d = dungeon(0, [room("gas", "Trap", 2, effect: POISON),
                    room("hall", "Trap", 0)])
    tough = hero("tough", :glory, 10) # highest health -> takes the gas hit
    frail = hero("frail", :riches, 6)
    res = PartyCrawlResolver.resolve(Party.new([tough, frail]), d)

    # The gas room hits only the highest-health hero; the other is untouched.
    gas_hits = res.log.select { |s| s[:encounter].id == "gas" }
    assert_equal [tough], gas_hits.map { |s| s[:hero] }
    assert_equal 2, gas_hits.first[:damage]
    # Only the poisoned hero ticks afterwards; the frail hero never takes damage.
    assert(res.log.select { |s| s[:encounter].id == "hall" }.all? { |s| s[:hero] == tough })
    assert(res.log.none? { |s| s[:hero] == frail })
  end

  def test_room_unreducible_bypasses_hero_damage_reduction
    rogue = hero("hero_rogue", :glory, 10) # party aura: -2 to trap rooms
    # Without the effect the rogue would take 4 - 2 = 2; unreducible forces full 4.
    d = dungeon(0, [room("arena", "Basic Trap", 4, effect: { "unreducible" => true })])
    res = PartyCrawlResolver.resolve(Party.new([rogue]), d)

    assert_equal 4, res.log.first[:damage]
  end

  def test_next_room_damage_lands_unreducible_in_the_following_room
    rogue = hero("hero_rogue", :glory, 20) # party aura: -2 to TRAP rooms
    # The spikes room is a monster (so the rogue's trap reduction skips its 2),
    # but the delayed hit lands in a trap hall — where, if it were reducible, the
    # rogue's -2 would apply. It does not: the delayed hit is always unreducible.
    d = dungeon(0, [room("spikes", "Basic Monster", 2, effect: { "next_room_damage" => 8 }),
                    room("hall", "Basic Trap", 0)])
    res = PartyCrawlResolver.resolve(Party.new([rogue]), d)

    damages = res.log.map { |s| s[:damage] }
    assert_equal [2, 8], damages
    assert_equal 20 - 10, res.log.last[:health_after]
  end

  def test_next_room_damage_clears_after_one_room
    d = dungeon(0, [room("spikes", "Basic Trap", 2, effect: { "next_room_damage" => 8 }),
                    room("hall_a", "Basic Trap", 0),
                    room("hall_b", "Basic Trap", 0)])
    res = PartyCrawlResolver.resolve(Party.new([hero("h", :glory, 30)]), d)

    # The 8 lands once (in hall_a) and never again.
    assert_equal [2, 8], res.log.map { |s| s[:damage] }
  end

  def test_draw_on_death_records_a_draw_for_the_owner
    d = dungeon(0, [room("siphon", "Basic Monster", 5, effect: { "draw_on_death" => "ability" })])
    res = PartyCrawlResolver.resolve(Party.new([hero("h", :glory, 4)]), d)

    assert_equal 1, res.deaths
    assert_equal({ "ability" => 1 }, res.draws.to_h)
  end

  def test_no_draw_when_no_hero_dies_in_the_room
    d = dungeon(0, [room("siphon", "Basic Monster", 2, effect: { "draw_on_death" => "room" })])
    res = PartyCrawlResolver.resolve(Party.new([hero("h", :glory, 10)]), d)

    assert_equal 0, res.deaths
    assert_equal({}, res.draws.to_h)
  end

  def test_a_dead_hero_stops_granting_its_party_aura
    cleric = hero("hero_cleric", :undead, 4) # undead aura: -4 to undead rooms
    tank = hero("tank", :glory, 10)
    # Zealots kills the cleric (4 unreducible to clerics); then an undead room
    # hits the tank — with the cleric dead, no -4 reduction applies.
    d = dungeon(0, [room("zealots", "Creature", 0, effect: party_hit("undead"), bait: { undead: 2 }),
                    room("crypt", "Basic Monster", 5, bait: { undead: 1 })])
    res = PartyCrawlResolver.resolve(Party.new([cleric, tank]), d)

    crypt_hit = res.log.find { |s| s[:encounter].id == "crypt" }
    assert_equal 5, crypt_hit[:damage] # full damage (would be 1 if the cleric still lived)
    refute_includes res.survivors, cleric
  end
end
