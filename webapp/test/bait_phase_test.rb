# frozen_string_literal: true

require_relative "test_helper"

class BaitPhaseTest < Minitest::Test
  class GameDouble
    attr_reader :players, :town

    def initialize(players, town, living: nil)
      @players = players
      @town = town
      @living = living || players
    end

    # Only living players' dungeons attract parties.
    def living_players
      @living
    end
  end

  def player_with_bait(name, bait, points: 0)
    player = Player.new(name)
    player.dungeon = Dungeon.new(Boss.new(id: "b#{name}", name: "Boss", damage: 1, bait: bait))
    player.points = points
    player
  end

  def hero(pref, courage: 1)
    Hero.new(id: "h#{pref}", name: pref.to_s, health: 5, preferred_bait: pref, courage: courage)
  end

  def party(*heroes)
    Party.new(heroes)
  end

  def test_party_enters_the_strictly_most_enticing_dungeon
    p1 = player_with_bait("1", { glory: 3 })
    p2 = player_with_bait("2", { glory: 1 })
    pty = party(hero(:glory))

    assert_equal p1, BaitPhase.target_for(GameDouble.new([p1, p2], [pty]), pty)
  end

  def test_eliminated_players_dungeon_does_not_attract
    p1 = player_with_bait("1", { glory: 3 }) # most enticing, but eliminated
    p2 = player_with_bait("2", { glory: 1 })
    pty = party(hero(:glory))
    # p1 is not among the living, so the party is drawn to p2 instead.
    assert_equal p2, BaitPhase.target_for(GameDouble.new([p1, p2], [pty], living: [p2]), pty)
  end

  def test_tie_leaves_the_party_with_no_target
    p1 = player_with_bait("1", { glory: 2 })
    p2 = player_with_bait("2", { glory: 2 })
    pty = party(hero(:glory))

    assert_nil BaitPhase.target_for(GameDouble.new([p1, p2], [pty]), pty)
  end

  def test_low_courage_party_has_no_target_for_a_dungeon_with_points
    p1 = player_with_bait("1", { glory: 3 }, points: 2)
    p2 = player_with_bait("2", { glory: 1 })
    pty = party(hero(:glory, courage: 1)) # courage 1 < 2 points

    assert_nil BaitPhase.target_for(GameDouble.new([p1, p2], [pty]), pty)
  end

  def test_courage_check_uses_current_points
    # The same party enters a 1-point dungeon but not once it climbs to 2 points.
    p1 = player_with_bait("1", { glory: 3 }, points: 1)
    p2 = player_with_bait("2", { glory: 1 })
    pty = party(hero(:glory, courage: 1))
    game = GameDouble.new([p1, p2], [pty])

    assert_equal p1, BaitPhase.target_for(game, pty)
    p1.points = 2
    assert_nil BaitPhase.target_for(game, pty) # now too timid
  end

  def test_combined_bait_counts_both_preferences
    p1 = player_with_bait("1", { glory: 1, riches: 1 }) # enticement 1 + 1 = 2
    p2 = player_with_bait("2", { glory: 1 })            # enticement 1 + 0 = 1
    pty = party(hero(:glory), hero(:riches))

    assert_equal p1, BaitPhase.target_for(GameDouble.new([p1, p2], [pty]), pty)
  end
end
