# frozen_string_literal: true

require_relative "hero_ability"

# Runs a single hero through a single dungeon and reports the outcome.
# Applies room/boss damage reduced by the hero's special ability (HeroAbility).
# Stateless; returns a result rather than mutating anything.
class CrawlResolver
  # The result of one crawl.
  # outcome: :died or :escaped
  # remaining_health: hero health after the crawl (<= 0 if died)
  # log: ordered list of { encounter:, damage:, health_after: } steps, where
  #      damage is the amount actually taken (after the hero's ability).
  Result = Struct.new(:outcome, :remaining_health, :log, keyword_init: true)

  # @param hero [Hero]
  # @param dungeon [Dungeon]
  # @return [Result]
  def self.resolve(hero, dungeon)
    ability = HeroAbility.lookup(hero)
    health = hero.health
    log = []

    dungeon.encounters.each do |encounter|
      damage = ability.reduced(encounter, encounter.damage)
      health -= damage
      log << { encounter: encounter, damage: damage, health_after: health }
      break if health <= 0
    end

    outcome = health <= 0 ? :died : :escaped
    Result.new(outcome: outcome, remaining_health: health, log: log)
  end
end
