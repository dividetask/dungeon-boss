# frozen_string_literal: true

require_relative "test_helper"

class RecruitmentPhaseTest < Minitest::Test
  class GameDouble
    attr_reader :town, :rng

    def initialize(town)
      @town = town
      @rng = Random.new(1)
    end

    def remove_party_from_town(party)
      @town.delete(party)
    end
  end

  def hero(id, bait)
    Hero.new(id: id, name: id, health: 5, preferred_bait: bait)
  end

  def lone(id, bait)
    Party.new([hero(id, bait)])
  end

  def test_lone_heroes_pair_preferring_a_different_bait
    g1 = lone("g1", :glory)
    g2 = lone("g2", :glory)
    u  = lone("u", :undead)
    town = [g1, g2, u]
    game = GameDouble.new(town.dup)

    RecruitmentPhase.run(game, [g1, g2, u])

    # g1 partners with the different-bait hero (u); g2 is the odd one out.
    assert_equal 2, game.town.size
    pair = game.town.find { |p| p.size == 2 }
    assert_equal %w[g1 u], pair.heroes.map(&:id)
    assert pair.named?, "a freshly formed party should be given a name"
    assert_includes game.town, g2
  end

  def test_existing_party_recruits_a_lone_hero
    pair = Party.new([hero("a", :glory), hero("b", :glory)])
    lone_hero = lone("c", :undead)
    game = GameDouble.new([pair, lone_hero])

    RecruitmentPhase.run(game, [pair, lone_hero])

    assert_equal [pair], game.town
    assert_equal %w[a b c], pair.heroes.map(&:id)
  end
end
