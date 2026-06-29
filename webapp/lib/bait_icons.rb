# frozen_string_literal: true

require_relative "bait"

# An immutable count of icons per bait type. Answers "how many of bait X?".
# A bait type that is absent counts as 0.
class BaitIcons
  # @param counts [Hash] raw map of bait name => count (from YAML or code)
  def initialize(counts = {})
    @counts = {}
    (counts || {}).each do |bait, count|
      next if count.to_i.zero?

      @counts[Bait.normalize(bait)] = Integer(count)
    end
    @counts.freeze
  end

  # Number of icons of the given bait type.
  def count(bait)
    @counts.fetch(Bait.normalize(bait), 0)
  end

  # Total icons across all bait types.
  def total
    @counts.values.sum
  end

  def to_h
    @counts.dup
  end

  # True if this set has at least as many icons of every type as `other`
  # (used to check an advanced room's bait requirement against a room).
  def contains?(other)
    other.to_h.all? { |bait, needed| count(bait) >= needed }
  end

  # True if this set and `other` have at least one bait type in common (used to
  # check an advanced room's bait requirement: it may replace any room that
  # shares at least one of its bait icons).
  def shares?(other)
    (to_h.keys & other.to_h.keys).any?
  end
end
