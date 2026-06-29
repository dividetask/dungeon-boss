# frozen_string_literal: true

require_relative "bait"

# A read-only summary of a dungeon: its total damage and its bait icons totalled
# by type across the boss and all rooms. Used for the player overview panel.
# Stateless beyond the dungeon it wraps.
class DungeonSummary
  def initialize(dungeon)
    @dungeon = dungeon
  end

  def boss_name
    @dungeon.boss.name
  end

  # Sum of every encounter's damage (rooms + boss).
  def total_damage
    sources.sum(&:damage)
  end

  # Hash of bait type => count, in canonical bait order, omitting zeros.
  def bait_totals
    Bait::ALL.each_with_object({}) do |bait, totals|
      count = sources.sum { |source| source.bait.count(bait) }
      totals[bait] = count if count.positive?
    end
  end

  # Hash of every bait type => count, in canonical order, including zeros.
  def all_bait_totals
    Bait::ALL.each_with_object({}) do |bait, totals|
      totals[bait] = sources.sum { |source| source.bait.count(bait) }
    end
  end

  private

  def sources
    @dungeon.rooms + [@dungeon.boss]
  end
end
