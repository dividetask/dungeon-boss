# frozen_string_literal: true

require_relative "test_helper"

# Boss effects are defined as data (see data/cards.yaml); these tests build the
# same effect specs inline and check BossEffect interprets them correctly.
class BossEffectTest < Minitest::Test
  def room(id, name, dmg, type: "Basic Monster", bait: { glory: 1 }, tags: [])
    Room.new(id: id, name: name, type: type, damage: dmg, bait: bait, tags: tags)
  end

  def hero(hp)
    Hero.new(id: "h", name: "Tank", health: hp, preferred_bait: :glory)
  end

  # rooms listed left-to-right (entrance first)
  def dungeon(boss, rooms)
    d = Dungeon.new(boss)
    rooms.reverse_each { |r| d.add_room_to_left(r) }
    d
  end

  def boss(effect)
    Boss.new(id: "b", name: "Boss", damage: 4, bait: {}, effect: effect)
  end

  # --- per-point room aura (Goblin Chieftain) ---

  def goblin_chieftain
    boss("room_bonuses" => [{ "match" => { "tag" => "goblin" }, "per_point" => 1 }])
  end

  def test_goblin_chieftain_boosts_goblin_tagged_rooms_by_points
    d = dungeon(goblin_chieftain, [room("goblins", "Goblins", 1, tags: ["goblin"]),
                                   room("spikes", "Stone Ball", 5)])
    res = PartyCrawlResolver.resolve(Party.new([hero(100)]), d, boss_bonus: 3)

    by_room = res.log.group_by { |s| s[:encounter].id }
    assert_equal 1 + 3, by_room["goblins"].first[:damage]  # tagged goblin: base 1 + 3 points
    assert_equal 5, by_room["spikes"].first[:damage]        # untagged: unaffected
    assert_equal 4 + 3, res.log.last[:damage]               # boss self: base 4 + 1×3 points
  end

  def test_a_goblin_named_room_without_the_tag_is_not_boosted
    d = dungeon(goblin_chieftain, [room("goblins", "Goblins", 1)]) # no tag
    res = PartyCrawlResolver.resolve(Party.new([hero(100)]), d, boss_bonus: 3)

    assert_equal 1, res.log.find { |s| s[:encounter].id == "goblins" }[:damage]
  end

  def test_plain_boss_grants_no_room_bonus_but_still_boosts_itself
    d = dungeon(boss(nil), [room("goblins", "Goblins", 1, tags: ["goblin"])])
    res = PartyCrawlResolver.resolve(Party.new([hero(100)]), d, boss_bonus: 3)

    assert_equal 1, res.log.find { |s| s[:encounter].id == "goblins" }[:damage] # no aura
    assert_equal 4 + 3, res.log.last[:damage] # default boss: base 4 + 1×3 points
  end

  # --- self per-point multiplier (Malevolent Spirit) ---

  def test_self_damage_per_point_multiplies_the_boss_bonus
    spirit = Boss.new(id: "ms", name: "Malevolent Spirit", damage: 2, bait: {},
                      effect: { "self_damage_per_point" => 3 })
    d = dungeon(spirit, [room("r", "Room", 1)])
    res = PartyCrawlResolver.resolve(Party.new([hero(100)]), d, boss_bonus: 2)

    assert_equal 2 + 3 * 2, res.log.last[:damage] # base 2 + 3 per point × 2 points
  end

  # --- flat room aura by type (Kobold Chieftain) ---

  def test_flat_bonus_to_trap_rooms
    kobold = boss("room_bonuses" => [{ "match" => { "type" => "trap" }, "flat" => 2 }])
    d = dungeon(kobold, [room("trap", "Spikes", 3, type: "Basic Trap"),
                         room("mon", "Skeletons", 3, type: "Basic Monster")])
    res = PartyCrawlResolver.resolve(Party.new([hero(100)]), d, boss_bonus: 5)

    by_room = res.log.group_by { |s| s[:encounter].id }
    assert_equal 3 + 2, by_room["trap"].first[:damage] # trap gets +2 (flat, not per point)
    assert_equal 3, by_room["mon"].first[:damage]      # monster unaffected
  end

  # --- flat room aura by type + bait (Necromancer) ---

  def test_flat_bonus_to_undead_creatures_only
    necro = boss("room_bonuses" => [{ "match" => { "type" => "creature", "bait" => "undead" }, "flat" => 2 }])
    d = dungeon(necro, [room("undead_mon", "Skeletons", 3, type: "Basic Monster", bait: { undead: 1 }),
                        room("live_mon", "Goblins", 3, type: "Basic Monster", bait: { glory: 1 }),
                        room("undead_trap", "Bones", 3, type: "Basic Trap", bait: { undead: 1 })])
    res = PartyCrawlResolver.resolve(Party.new([hero(100)]), d, boss_bonus: 1)

    by_room = res.log.group_by { |s| s[:encounter].id }
    assert_equal 3 + 2, by_room["undead_mon"].first[:damage] # undead creature: +2
    assert_equal 3, by_room["live_mon"].first[:damage]       # creature, wrong bait: no bonus
    assert_equal 3, by_room["undead_trap"].first[:damage]    # undead, but a trap: no bonus
  end
end
