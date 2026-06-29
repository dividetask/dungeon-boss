# frozen_string_literal: true

require_relative "bait"

# Totals the icons of a single bait type across a dungeon (boss + all rooms).
# This is a dungeon's "enticement" for a hero whose preferred bait is that type.
# Stateless.
class BaitCounter
  # @param dungeon [Dungeon]
  # @param bait [Symbol, String] the bait type to count
  # @return [Integer] total matching icons across boss and rooms
  def self.enticement(dungeon, bait)
    bait = Bait.normalize(bait)
    sources = dungeon.rooms + [dungeon.boss]
    sources.sum { |source| source.bait.count(bait) }
  end
end
