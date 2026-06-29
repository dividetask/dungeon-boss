# frozen_string_literal: true

require_relative "../party_namer"

# Recruitment phase (after the crawl). Heroes/parties that did not enter a
# dungeon this turn (unenticed or too afraid) band together for next time:
#   - each waiting multi-hero party recruits one waiting lone hero, and
#   - the remaining waiting lone heroes pair off,
# both in board (town) order, preferring a partner with a different preferred
# bait. An odd lone hero is left out and waits alone.
#
# Merged parties replace their members in town; the merged party persists.
class RecruitmentPhase
  # @param waiting [Array<Party>] the parties (in town order) that did not enter
  def self.run(game, waiting)
    lones = waiting.select(&:lone?)
    parties = waiting.reject(&:lone?)

    # Existing parties each pull in one lone hero.
    parties.each do |party|
      lone = pick_partner(lones, party.heroes.map(&:preferred_bait))
      next unless lone

      lones.delete(lone)
      party.add(lone.heroes.first)
      game.remove_party_from_town(lone)
    end

    # Remaining lone heroes pair up into newly-named parties.
    until lones.size < 2
      first = lones.shift
      partner = pick_partner(lones, first.heroes.map(&:preferred_bait)) || lones.first
      lones.delete(partner)
      first.add(partner.heroes.first)
      first.name = PartyNamer.generate(game.rng)
      game.remove_party_from_town(partner)
    end
    # Any leftover lone hero is the odd one out and stays alone.
    game
  end

  # Prefer a lone hero whose bait is not already represented; else the first.
  def self.pick_partner(lones, taken_baits)
    lones.find { |lone| !taken_baits.include?(lone.heroes.first.preferred_bait) } || lones.first
  end
end
