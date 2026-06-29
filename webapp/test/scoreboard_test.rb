# frozen_string_literal: true

require_relative "test_helper"

class ScoreboardTest < Minitest::Test
  def player(name, points: 0, wounds: 0)
    p = Player.new(name)
    p.points = points
    p.wounds = wounds
    p
  end

  def test_not_over_during_normal_play
    refute Scoreboard.over?([player("A", points: 3, wounds: 1), player("B", points: 2)])
  end

  def test_over_when_a_player_reaches_ten_points
    assert Scoreboard.over?([player("A", points: 10), player("B")])
  end

  def test_over_when_all_but_one_are_eliminated
    assert Scoreboard.over?([player("A", wounds: 5), player("B", points: 1)])
  end

  def test_score_is_points_minus_two_per_wound
    assert_equal 4, Scoreboard.score(player("A", points: 8, wounds: 2))
  end

  def test_five_wounds_loses_even_with_points
    a = player("A", points: 12, wounds: 5) # eliminated despite high points
    b = player("B", points: 1)

    assert_equal b, Scoreboard.winner([a, b], a)
  end

  def test_tie_goes_to_the_ender
    a = player("A", points: 4)
    b = player("B", points: 4)

    assert_equal b, Scoreboard.winner([a, b], b)
    assert_equal a, Scoreboard.winner([a, b], a)
  end
end
