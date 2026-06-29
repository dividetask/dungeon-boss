# frozen_string_literal: true

require_relative "test_helper"

class BaitCounterTest < Minitest::Test
  def boss(bait)
    Boss.new(id: "b", name: "Boss", damage: 1, bait: bait)
  end

  def room(bait)
    Room.new(id: "r", name: "Room", type: "trap", damage: 1, bait: bait)
  end

  def test_sums_matching_bait_across_boss_and_rooms
    dungeon = Dungeon.new(boss(undead: 2, glory: 1))
    dungeon.add_room_to_left(room(undead: 1))
    dungeon.add_room_to_left(room(riches: 5))

    assert_equal 3, BaitCounter.enticement(dungeon, :undead)
    assert_equal 1, BaitCounter.enticement(dungeon, :glory)
    assert_equal 5, BaitCounter.enticement(dungeon, :riches)
    assert_equal 0, BaitCounter.enticement(dungeon, :power)
  end
end
