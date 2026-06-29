# frozen_string_literal: true

require_relative "dungeon"
require_relative "placed_room"
require_relative "party_crawl_resolver"

# Forecasts how a set of parties would fare crawling a dungeon, without touching
# the real game. Used by LogicAgent to score a candidate placement: how many
# heroes it would kill (points), how many parties would survive (wounds), and how
# much damage it would deal. Holds no state of its own.
#
# PartyCrawlResolver mutates grow-on-death rooms and tracks per-crawl health, so
# each forecast runs against a *fresh clone* of the dungeon and the resolver gets
# the dungeon untouched by earlier simulations.
class DungeonForecast
  # kills:        total heroes that die across all parties (= points scored)
  # wounds:       parties with at least one survivor (= wounds taken, one each)
  # total_damage: post-reduction damage dealt to every hero, summed
  # hero_count:   heroes who entered (denominator for the average)
  Outcome = Struct.new(:kills, :wounds, :total_damage, :hero_count, keyword_init: true) do
    def avg_damage
      hero_count.zero? ? 0.0 : total_damage.to_f / hero_count
    end
  end

  # @param dungeon [Dungeon] the dungeon to test (left untouched)
  # @param parties [Array<Party>] the parties to send through, one crawl each
  # @param boss_bonus [Integer] the owner's points (the boss deals this much extra)
  def self.run(dungeon, parties, boss_bonus: 0)
    kills = 0
    wounds = 0
    total_damage = 0
    hero_count = 0

    parties.each do |party|
      result = PartyCrawlResolver.resolve(party, clone(dungeon), boss_bonus: boss_bonus)
      kills += result.deaths
      wounds += 1 unless result.survivors.empty?
      total_damage += result.log.sum { |entry| entry[:damage] }
      hero_count += party.size
    end

    Outcome.new(kills: kills, wounds: wounds, total_damage: total_damage, hero_count: hero_count)
  end

  # A deep-enough copy: same boss and rooms (in order), preserving each slot's
  # upgrade and permanent grow bonus, so the resolver can mutate it freely.
  def self.clone(dungeon)
    copy = Dungeon.new(dungeon.boss)
    dungeon.rooms.reverse_each { |placed| copy.add_room_to_left(placed.base_room) }
    dungeon.rooms.each_with_index do |placed, index|
      copy.rooms[index].upgrade = placed.upgrade
      copy.rooms[index].grow = placed.grow
    end
    copy
  end
end
