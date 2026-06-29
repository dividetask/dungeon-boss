# frozen_string_literal: true

# Decides whether the game is over and who won.
#
# - A player with 5+ wounds is eliminated (loses outright).
# - The game ends when any player reaches 10 points, or when all but one player
#   has been eliminated.
# - Final score for a surviving player is points − 2 × wounds; highest wins.
# - A tie is broken in favour of the player who ended the game.
module Scoreboard
  LOSS_WOUNDS = 5
  WIN_POINTS = 10

  Standing = Struct.new(:player, :score, :eliminated, keyword_init: true)

  module_function

  def eliminated?(player)
    player.wounds >= LOSS_WOUNDS
  end

  def score(player)
    player.points - 2 * player.wounds
  end

  def survivors(players)
    players.reject { |player| eliminated?(player) }
  end

  def over?(players)
    players.any? { |player| player.points >= WIN_POINTS } || survivors(players).size <= 1
  end

  # Standings for display, best score first (eliminated players last).
  def standings(players)
    players
      .map { |p| Standing.new(player: p, score: score(p), eliminated: eliminated?(p)) }
      .sort_by { |s| [s.eliminated ? 1 : 0, -s.score] }
  end

  # The winning player. `ender` is the player who triggered the game's end and
  # wins any tie.
  def winner(players, ender)
    contenders = survivors(players)
    return ender if contenders.empty?

    best = contenders.map { |player| score(player) }.max
    top = contenders.select { |player| score(player) == best }
    return top.first if top.size == 1

    top.include?(ender) ? ender : top.first
  end
end
