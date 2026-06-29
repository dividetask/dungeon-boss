# frozen_string_literal: true

require_relative "test_helper"

class UpgradeTest < Minitest::Test
  def room
    Room.new(id: "r", name: "Room", type: "Basic Trap", damage: 2, bait: { riches: 1 })
  end

  def test_placed_room_without_upgrade_matches_the_base
    placed = PlacedRoom.new(room)

    assert_equal 2, placed.damage
    assert_equal 1, placed.bait.count(:riches)
    assert_nil placed.upgrade
  end

  def test_damage_upgrade_adds_bonus_damage
    placed = PlacedRoom.new(room, upgrade: Upgrade.new(id: "u", name: "Walls", bonus_damage: 2))

    assert_equal 4, placed.damage
  end

  def test_bait_upgrade_adds_a_bait_icon
    placed = PlacedRoom.new(room, upgrade: Upgrade.new(id: "u", name: "Totem", bait: { glory: 1 }))

    assert_equal 1, placed.bait.count(:glory)
    assert_equal 1, placed.bait.count(:riches)
  end

  def test_dungeon_apply_upgrade_replaces_and_returns_the_old_one
    dungeon = Dungeon.new(Boss.new(id: "b", name: "B", damage: 1, bait: {}))
    dungeon.add_room_to_left(room)
    first = Upgrade.new(id: "u1", name: "Walls", bonus_damage: 2)
    second = Upgrade.new(id: "u2", name: "Totem", bait: { glory: 1 })

    assert_nil dungeon.apply_upgrade(0, first)
    assert_equal 4, dungeon.rooms.first.damage
    assert_equal first, dungeon.apply_upgrade(0, second) # one upgrade per room
    assert_equal 2, dungeon.rooms.first.damage # back to base + nothing
    assert_equal 1, dungeon.rooms.first.bait.count(:glory)
  end

  def test_deck_has_upgrades_about_one_per_four_rooms
    lib = CardLibrary.load(CARDS_PATH)

    assert_operator lib.upgrades.size, :>, 0
    assert_in_delta 4.0, lib.rooms.size.to_f / lib.upgrades.size, 0.4
  end

  def test_replacing_a_room_loses_its_upgrade
    dungeon = Dungeon.new(Boss.new(id: "b", name: "B", damage: 1, bait: {}))
    dungeon.add_room_to_left(room)
    dungeon.apply_upgrade(0, Upgrade.new(id: "u", name: "Walls", bonus_damage: 2))

    replaced = dungeon.replace_room(0, Room.new(id: "r2", name: "R2", type: "trap", damage: 1, bait: {}))

    assert_equal "r", replaced.base_room.id
    refute_nil replaced.upgrade
    assert_nil dungeon.rooms.first.upgrade # the new room has no upgrade
  end
end
