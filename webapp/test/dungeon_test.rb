# frozen_string_literal: true

require_relative "test_helper"

class DungeonTest < Minitest::Test
  def boss
    Boss.new(id: "b", name: "Boss", damage: 1, bait: {})
  end

  def room(id)
    Room.new(id: id, name: id, type: "trap", damage: 1, bait: {})
  end

  def test_caps_rooms_at_max
    dungeon = Dungeon.new(boss)
    Dungeon::MAX_ROOMS.times { |i| dungeon.add_room_to_left(room("r#{i}")) }

    assert dungeon.full?
    assert_raises(RuntimeError) { dungeon.add_room_to_left(room("overflow")) }
  end

  def test_replace_room_returns_old_room
    dungeon = Dungeon.new(boss)
    dungeon.add_room_to_left(room("a")) # index 0
    dungeon.add_room_to_left(room("b")) # index 0, "a" now index 1

    old = dungeon.replace_room(0, room("c"))

    assert_equal "b", old.id
    assert_equal %w[c a], dungeon.rooms.map(&:id)
  end

  def test_summary_totals_damage_and_bait
    dungeon = Dungeon.new(Boss.new(id: "b", name: "Medusa", damage: 7, bait: { glory: 1 }))
    dungeon.add_room_to_left(Room.new(id: "r1", name: "R1", type: "trap", damage: 2, bait: { glory: 1 }))
    dungeon.add_room_to_left(Room.new(id: "r2", name: "R2", type: "trap", damage: 3, bait: { riches: 5 }))

    summary = DungeonSummary.new(dungeon)

    assert_equal "Medusa", summary.boss_name
    assert_equal 12, summary.total_damage
    assert_equal({ glory: 2, riches: 5 }, summary.bait_totals)
  end
end
