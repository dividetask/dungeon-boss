# frozen_string_literal: true

# Per-crawl modifiers produced by ability cards and discard-to-boost decisions
# made just before a party crawls. They affect only that one crawl. Keyed by
# room index within the dungeon's encounters.
class CrawlModifiers
  def initialize
    @plus = Hash.new(0)   # +damage (Reinforcements)
    @zero = {}            # damage forced to 0 (Sabotage)
    @set = {}             # damage overridden to a fixed value (discard-to-boost)
    @unreducible = {}     # damage cannot be reduced (Expose Weakness / Troll)
    @retreat_at = nil     # the party turns back at this encounter index (Retreat)
  end

  def add_damage(index, amount = 2) = @plus[index] += amount
  def zero!(index) = @zero[index] = true
  def set_damage!(index, value) = @set[index] = Integer(value)
  def boost6!(index) = set_damage!(index, 6) # convenience for the common case
  def unreducible!(index) = @unreducible[index] = true
  def retreat!(index) = @retreat_at = Integer(index) # crawl stops before this encounter

  def retreating? = !@retreat_at.nil?
  def retreat_index = @retreat_at
  def retreats_at?(index) = @retreat_at && index >= @retreat_at
  def zero?(index) = @zero.fetch(index, false)
  def set?(index) = @set.key?(index)
  def set_value(index) = @set[index]
  def reducible?(index) = !@unreducible.fetch(index, false)
  def bonus(index) = @plus[index]

  # Whether a per-room boost/override has already been applied (once per room).
  def boosted?(index)
    @set.key?(index) || @unreducible.fetch(index, false) ||
      @zero.fetch(index, false) || @plus[index].positive?
  end
end
