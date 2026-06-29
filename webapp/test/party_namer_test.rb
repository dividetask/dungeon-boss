# frozen_string_literal: true

require_relative "test_helper"

class PartyNamerTest < Minitest::Test
  def test_generates_a_the_adjective_noun_name
    name = PartyNamer.generate(Random.new(1))

    assert_match(/\AThe \S+ \S+\z/, name)
  end

  def test_lone_party_uses_the_hero_name
    hero = Hero.new(id: "h", name: "Rogue", health: 6, preferred_bait: :riches)
    party = Party.new([hero])

    refute party.named?
    assert_equal "Rogue", party.display_name
  end

  def test_named_party_uses_its_name
    party = Party.new([], name: "The Grim Few")

    assert party.named?
    assert_equal "The Grim Few", party.display_name
  end
end
