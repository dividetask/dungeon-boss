# frozen_string_literal: true

require_relative "../party_crawl_resolver"
require_relative "../crawl_modifiers"

# Crawl phase. A party is sent into its dungeon as a unit. Each hero that dies
# earns the controlling player a point and is removed from the party; if at
# least one member survives a full crawl, the player gains exactly one wound
# (one per party). A Retreat makes the party turn back at a chosen room: the
# rooms before it still resolve (so deaths there still score), but the party
# escapes the rest and the owner takes no wound. A party only leaves town when
# all of its members die. Orchestration only; the math is in PartyCrawlResolver.
class CrawlPhase
  # One resolved party crawl, for display/inspection. `retreated` is true when a
  # Retreat turned the party back before finishing the dungeon. `boss_bonus` is
  # the owner's point total AT THE START of this crawl — the per-point bonus that
  # actually applied to it (points scored mid-crawl do not strengthen it until
  # the next crawl), so the UI can replay the dungeon at the right damage.
  Outcome = Struct.new(:player, :party, :result, :retreated, :boss_bonus, keyword_init: true)

  def self.resolve_party(game, player, party, modifiers = CrawlModifiers.new)
    # The boss deals extra damage equal to its owner's points, snapshotted now so
    # kills during this crawl only count toward the next one.
    boss_bonus = player.points
    result = PartyCrawlResolver.resolve(party, player.dungeon, boss_bonus: boss_bonus,
                                                               modifiers: modifiers)

    result.deaths.times { player.gain_point }
    # A wound is only taken when a hero survives a FULL crawl — retreaters escape.
    player.gain_wound if !modifiers.retreating? && !result.survivors.empty?

    result.dead_heroes.each { |hero| party.remove(hero) }
    game.remove_party_from_town(party) if party.empty?

    Outcome.new(player: player, party: party, result: result,
                retreated: modifiers.retreating?, boss_bonus: boss_bonus)
  end
end
