# frozen_string_literal: true

require_relative "test_helper"

class AdvancedRoomTest < Minitest::Test
  def boss
    Boss.new(id: "b", name: "Boss", damage: 1, bait: {})
  end

  def basic(id, bait)
    Room.new(id: id, name: id, type: "Basic Trap", damage: 2, bait: bait)
  end

  def advanced(id, bait)
    Room.new(id: id, name: id, type: "Trap", damage: 2, bait: bait, advanced: true)
  end

  # Minimal game double for BuildPhase.place.
  class GameDouble
    attr_reader :room_deck

    def initialize
      @room_deck = Deck.new([])
    end
  end

  def player_with(rooms)
    player = Player.new("P")
    player.dungeon = Dungeon.new(Boss.new(id: "bz", name: "Boss", damage: 1, bait: {}))
    rooms.each { |r| player.dungeon.add_room_to_left(r) }
    player
  end

  def test_bait_icons_contains
    assert BaitIcons.new(power: 2, glory: 1).contains?(BaitIcons.new(power: 2))
    refute BaitIcons.new(power: 1).contains?(BaitIcons.new(power: 2))
    assert BaitIcons.new(power: 1, glory: 1).contains?(BaitIcons.new(power: 1, glory: 1))
    refute BaitIcons.new(glory: 1).contains?(BaitIcons.new(power: 1))
  end

  def test_bait_icons_shares
    assert BaitIcons.new(power: 1, glory: 1).shares?(BaitIcons.new(power: 2))   # power in common
    assert BaitIcons.new(power: 1).shares?(BaitIcons.new(power: 2, glory: 3))   # power in common
    refute BaitIcons.new(glory: 1).shares?(BaitIcons.new(power: 1))             # nothing in common
    refute BaitIcons.new({}).shares?(BaitIcons.new(power: 1))                   # empty shares nothing
  end

  def test_advanced_room_replaces_a_matching_room
    target = basic("r0", { power: 1, glory: 1 })
    player = player_with([target]) # index 0
    succubus = advanced("adv", { power: 1, glory: 1 })
    player.add_room_to_hand(succubus)
    game = GameDouble.new

    BuildPhase.place(game, player, "adv", 0)

    assert_equal 1, player.dungeon.rooms.size # replaced, not added
    assert_same succubus, player.dungeon.rooms.first.base_room
  end

  def test_advanced_room_replaces_a_room_sharing_one_bait
    # The room has only one power icon; the advanced room asks for two. Under the
    # relaxed rule a single shared bait type is enough to place it.
    target = basic("r0", { power: 1 })
    player = player_with([target])
    antimagic = advanced("adv", { power: 2 })
    player.add_room_to_hand(antimagic)
    game = GameDouble.new

    BuildPhase.place(game, player, "adv", 0)

    assert_same antimagic, player.dungeon.rooms.first.base_room
  end

  def test_advanced_room_rejects_a_non_matching_room
    target = basic("r0", { glory: 1 }) # no power
    player = player_with([target])
    antimagic = advanced("adv", { power: 2 })
    player.add_room_to_hand(antimagic)
    game = GameDouble.new

    assert_raises(ArgumentError) { BuildPhase.place(game, player, "adv", 0) }
    assert_equal "r0", player.dungeon.rooms.first.id # unchanged
  end

  def test_library_deck_ratio_is_about_four_one_one
    lib = CardLibrary.load(CARDS_PATH)
    basics = lib.rooms.size.to_f
    assert_in_delta 4.0, basics / lib.upgrades.size, 0.4
    assert_in_delta 4.0, basics / lib.advanced_rooms.size, 0.4
  end
end
