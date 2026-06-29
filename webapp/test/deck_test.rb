# frozen_string_literal: true

require_relative "test_helper"

class DeckTest < Minitest::Test
  def test_reshuffles_discards_when_draw_pile_empty
    deck = Deck.new(%w[a], rng: Random.new(1))
    drawn = deck.draw
    deck.discard(drawn)
    deck.discard("b")

    # Draw pile is empty; next draw must pull from the reshuffled discards.
    refute deck.empty?
    next_card = deck.draw
    assert_includes %w[a b], next_card
  end

  def test_empty_only_when_both_piles_empty
    deck = Deck.new(%w[only], rng: Random.new(1))
    refute deck.empty?
    deck.draw
    assert deck.empty?
    assert_nil deck.draw
  end
end
