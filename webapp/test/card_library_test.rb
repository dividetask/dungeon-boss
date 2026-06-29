# frozen_string_literal: true

require_relative "test_helper"

class CardLibraryTest < Minitest::Test
  def setup
    @lib = CardLibrary.load(CARDS_PATH)
  end

  def test_copies_are_distinct_instances
    mages = @lib.heroes.select { |h| h.id == "hero_mage" }
    assert_operator mages.size, :>, 1
    assert_equal mages.size, mages.map(&:object_id).uniq.size,
                 "hero copies must be distinct objects (two Mages must not be the same instance)"

    rooms = @lib.rooms.select { |r| r.id == "room_skeletons" }
    assert_equal rooms.size, rooms.map(&:object_id).uniq.size
  end
end
