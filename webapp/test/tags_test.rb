# frozen_string_literal: true

require_relative "test_helper"

class TagsTest < Minitest::Test
  def test_membership_is_case_insensitive_and_normalized
    tags = Tags.new(["Goblin", " UNDEAD "])
    assert tags.include?("goblin")
    assert tags.include?("GOBLIN")
    assert tags.include?("undead")
    refute tags.include?("trap")
  end

  def test_blank_and_duplicate_tags_are_dropped
    tags = Tags.new(["goblin", "goblin", "", nil])
    assert_equal ["goblin"], tags.to_a
  end

  def test_empty_by_default
    assert Tags.new.empty?
    assert Tags.new(nil).empty?
  end

  def test_union_merges_two_sets
    merged = Tags.new(["goblin"]) | Tags.new(["undead", "goblin"])
    assert_equal %w[goblin undead], merged.to_a
  end

  def test_a_card_carries_its_tags
    room = Room.new(id: "r", name: "Goblins", type: "Basic Monster",
                    damage: 1, bait: { glory: 1 }, tags: %w[goblin monster])
    assert room.tags.include?("goblin")
    assert room.tags.include?("monster")
  end

  def test_placed_room_merges_upgrade_tags
    room = Room.new(id: "r", name: "Goblins", type: "Basic Monster",
                    damage: 1, bait: {}, tags: ["goblin"])
    upgrade = Upgrade.new(id: "u", name: "Banner", bait: {}, tags: ["rallied"])
    placed = PlacedRoom.new(room, upgrade: upgrade)

    assert placed.tags.include?("goblin")
    assert placed.tags.include?("rallied")
  end
end
