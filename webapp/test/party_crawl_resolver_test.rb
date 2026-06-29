# frozen_string_literal: true

require_relative "test_helper"

class PartyCrawlResolverTest < Minitest::Test
  def dungeon(boss_damage, room_damages)
    d = Dungeon.new(Boss.new(id: "b", name: "Boss", damage: boss_damage, bait: {}))
    room_damages.reverse_each.with_index do |dmg, i|
      d.add_room_to_left(Room.new(id: "r#{i}", name: "R#{i}", type: "trap", damage: dmg, bait: {}))
    end
    d
  end

  def test_party_continues_after_a_death_until_all_die
    a = Hero.new(id: "a", name: "A", health: 4, preferred_bait: :glory)
    b = Hero.new(id: "b", name: "B", health: 4, preferred_bait: :glory)
    # entrance dmg 4 (kills A), boss dmg 4 (kills B)
    res = PartyCrawlResolver.resolve(Party.new([a, b]), dungeon(4, [4]))

    assert_equal 2, res.log.size
    assert_equal [a, b], res.log.map { |s| s[:hero] } # B is hit after A dies
    assert_equal 2, res.deaths
    assert_empty res.survivors
  end

  def test_boss_deals_extra_damage_equal_to_points
    hero = Hero.new(id: "h", name: "H", health: 10, preferred_bait: :glory)
    d = Dungeon.new(Boss.new(id: "b", name: "Boss", damage: 4, bait: {}))
    d.add_room_to_left(Room.new(id: "r", name: "R", type: "trap", damage: 0, bait: {}))

    # boss_bonus 3 -> boss hits for 4 + 3 = 7; hero 10 -> 3 left.
    res = PartyCrawlResolver.resolve(Party.new([hero]), d, boss_bonus: 3)
    boss_step = res.log.last

    assert_equal 7, boss_step[:damage]
    assert_equal 3, boss_step[:health_after]
  end

  def single_room_dungeon(dmg, bait: {})
    d = Dungeon.new(Boss.new(id: "b", name: "Boss", damage: 0, bait: {}))
    d.add_room_to_left(Room.new(id: "r", name: "R", type: "Basic Trap", damage: dmg, bait: bait))
    d
  end

  def test_modifiers_change_a_room_s_damage
    hero = Hero.new(id: "h", name: "H", health: 20, preferred_bait: :glory)

    plus = CrawlModifiers.new.tap { |m| m.add_damage(0) }
    assert_equal 4, PartyCrawlResolver.resolve(Party.new([hero]), single_room_dungeon(2), modifiers: plus).log.first[:damage]

    zero = CrawlModifiers.new.tap { |m| m.zero!(0) }
    # zeroed room deals nothing -> only the boss (0) -> no log entries for the room
    res = PartyCrawlResolver.resolve(Party.new([hero]), single_room_dungeon(2), modifiers: zero)
    assert(res.log.none? { |s| s[:encounter].id == "r" })

    six = CrawlModifiers.new.tap { |m| m.boost6!(0) }
    assert_equal 6, PartyCrawlResolver.resolve(Party.new([hero]), single_room_dungeon(2), modifiers: six).log.first[:damage]
  end

  def test_discard_boost_adds_to_the_rooms_upgraded_damage
    hero = Hero.new(id: "h", name: "H", health: 30, preferred_bait: :glory)
    d = Dungeon.new(Boss.new(id: "b", name: "Boss", damage: 0, bait: {}))
    d.add_room_to_left(Room.new(id: "r", name: "Collapsing Tunnel", type: "Trap", damage: 2, bait: {}))
    d.apply_upgrade(0, Upgrade.new(id: "u", name: "Reinforced Walls", bonus_damage: 3, bait: {}))

    # Room's effective damage is 2 + 3 (upgrade) = 5. A discard-to-boost (+4)
    # adds on top -> 9 (the upgrade is preserved, not replaced by a flat value).
    boost = CrawlModifiers.new.tap { |m| m.add_damage(0, 4) }
    res = PartyCrawlResolver.resolve(Party.new([hero]), d, modifiers: boost)

    assert_equal 9, res.log.first[:damage]
  end

  def test_unreducible_modifier_ignores_party_auras
    cleric = Hero.new(id: "hero_cleric", name: "Cleric", health: 20, preferred_bait: :undead)
    mods = CrawlModifiers.new.tap { |m| m.unreducible!(0) }
    # undead room would normally be reduced by 4; unreducible -> full 5
    res = PartyCrawlResolver.resolve(Party.new([cleric]), single_room_dungeon(5, bait: { undead: 1 }), modifiers: mods)

    assert_equal 5, res.log.first[:damage]
  end

  def test_two_heroes_with_the_same_id_are_handled_independently
    # Distinct objects sharing an id (as the deck now produces) must not collapse.
    m1 = Hero.new(id: "hero_mage", name: "Mage", health: 4, preferred_bait: :power)
    m2 = Hero.new(id: "hero_mage", name: "Mage", health: 4, preferred_bait: :power)
    res = PartyCrawlResolver.resolve(Party.new([m1, m2]), dungeon(4, [4]))

    assert_equal 2, res.deaths
    assert_equal [m1, m2], res.log.map { |s| s[:hero] }
  end
end
