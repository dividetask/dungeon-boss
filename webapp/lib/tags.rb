# frozen_string_literal: true

# An immutable, order-preserving set of lowercase tag strings carried by a card
# (e.g. "goblin", "undead"). Tags classify cards so effects can target a whole
# group at once — the Goblin Chieftain boosts every card tagged "goblin". A card
# may have any number of tags. Knows nothing about game rules.
class Tags
  # @param raw [Array, String, nil] tag(s) from YAML or code
  def initialize(raw = [])
    @set = Array(raw).map { |t| t.to_s.downcase.strip }.reject(&:empty?).uniq.freeze
  end

  # True if this card carries the given tag (case-insensitive).
  def include?(tag)
    @set.include?(tag.to_s.downcase)
  end

  def empty?
    @set.empty?
  end

  def to_a
    @set.dup
  end

  # The union of two tag sets (used to combine a room with its upgrade).
  def |(other)
    Tags.new(@set + other.to_a)
  end

  def ==(other)
    other.is_a?(Tags) && other.to_a == @set
  end
end
