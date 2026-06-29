# frozen_string_literal: true

# A drawable, discardable, shuffleable pile of cards. Generic over card type.
# Knows nothing about game rules — only how to draw and discard.
class Deck
  # @param cards [Array] the cards to seed the draw pile with
  # @param rng [Random] optional rng for deterministic shuffles (testing)
  def initialize(cards = [], rng: Random.new)
    @draw_pile = cards.dup
    @discard_pile = []
    @rng = rng
  end

  def shuffle!
    @draw_pile.shuffle!(random: @rng)
    self
  end

  # True only when both piles are empty (nothing left to draw or reshuffle).
  def empty?
    @draw_pile.empty? && @discard_pile.empty?
  end

  def size
    @draw_pile.size
  end

  # Draw a single card. If the draw pile is empty, reshuffle the discard pile
  # back into it first. Returns nil only when both piles are empty.
  def draw
    reshuffle_discards if @draw_pile.empty?
    @draw_pile.shift
  end

  # Draw up to n cards (fewer if the pile runs out).
  def draw_many(n)
    Array.new(n) { draw }.compact
  end

  def discard(card)
    @discard_pile << card if card
    self
  end

  # Take a specific card back out of the discard pile (e.g. to undo a discard).
  # Returns the card if it was there, else nil.
  def reclaim(card)
    @discard_pile.delete(card) ? card : nil
  end

  private

  # Move the discard pile into the draw pile and shuffle it.
  def reshuffle_discards
    return if @discard_pile.empty?

    @draw_pile = @discard_pile.shuffle(random: @rng)
    @discard_pile = []
  end
end
