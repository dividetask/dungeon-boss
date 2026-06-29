# frozen_string_literal: true

require_relative "../bait_counter"

# Bait. Each party is drawn toward the single most enticing dungeon (enticement =
# the combined count of every member's preferred bait across that dungeon — a
# bait shared by two members counts twice). A tie for the top enticement leaves
# the party unenticed.
#
# An enticed party only enters if its combined courage is at least the target
# dungeon owner's points. Bait is combined with the Crawl phase: the game asks
# `target_for` for each party right before it would enter, so the courage check
# uses the owner's CURRENT points (which rise as earlier parties die). A party
# that does not enter is left for Recruitment. Orchestration only; math in
# BaitCounter.
class BaitPhase
  # The dungeon (player) this party enters right now, or nil if it stays in
  # town — unenticed (tie/zero), or too timid for the owner's current points.
  def self.target_for(game, party)
    player = most_enticing_player(game, party)
    return nil unless player && party.courage >= player.points

    player
  end

  # The player whose dungeon is strictly most enticing to the party, or nil on a
  # tie for the top score. Eliminated players' dungeons do not attract parties.
  def self.most_enticing_player(game, party)
    scored = game.living_players.map { |player| [player, enticement(player.dungeon, party)] }
    top = scored.map { |_, score| score }.max
    winners = scored.select { |_, score| score == top }
    winners.size == 1 ? winners.first.first : nil
  end

  # Combined enticement: sum each member's preferred-bait count for the dungeon.
  def self.enticement(dungeon, party)
    party.heroes.sum { |hero| BaitCounter.enticement(dungeon, hero.preferred_bait) }
  end
end
