# frozen_string_literal: true

require_relative "test_helper"

class DungeonForecastTest < Minitest::Test
  def dungeon(boss_damage, room_damages)
    d = Dungeon.new(Boss.new(id: "b", name: "Boss", damage: boss_damage, bait: {}))
    room_damages.reverse_each.with_index do |dmg, i|
      d.add_room_to_left(Room.new(id: "r#{i}", name: "R#{i}", type: "trap", damage: dmg, bait: {}))
    end
    d
  end

  def hero(health)
    Hero.new(id: "h#{health}#{rand(1000)}", name: "H", health: health, preferred_bait: :glory)
  end

  def test_counts_kills_wounds_and_damage
    # entrance deals 5, boss deals 0. A 4-health hero dies; a 6-health hero lives.
    parties = [Party.new([hero(4)]), Party.new([hero(6)])]
    outcome = DungeonForecast.run(dungeon(0, [5]), parties)

    assert_equal 1, outcome.kills          # the 4-health hero
    assert_equal 1, outcome.wounds          # the 6-health hero's party survives
    assert_equal 10, outcome.total_damage   # 5 to each hero
    assert_in_delta 5.0, outcome.avg_damage
  end

  def test_empty_town_is_a_zero_outcome
    outcome = DungeonForecast.run(dungeon(3, [3]), [])

    assert_equal 0, outcome.kills
    assert_equal 0, outcome.wounds
    assert_equal 0.0, outcome.avg_damage
  end

  def test_clone_does_not_mutate_the_original_grow_room
    base = Dungeon.new(Boss.new(id: "b", name: "Boss", damage: 0, bait: {}))
    # A grow-on-death room: it gains +1 permanently per death during a crawl.
    succubus = Room.new(id: "succ", name: "Succubus", type: "Creature", damage: 4,
                        bait: { power: 1 }, effect: { "grows_on_death" => true })
    base.add_room_to_left(succubus)

    DungeonForecast.run(base, [Party.new([hero(4)])]) # would kill the hero, growing the clone

    assert_equal 0, base.rooms.first.grow, "the real dungeon's grow must be untouched"
  end
end
