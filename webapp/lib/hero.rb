# frozen_string_literal: true

require_relative "bait"
require_relative "tags"

# One hero card's data. Heroes crawl dungeons.
# Immutable. `ability_text` describes the hero's special ability (applied during
# the crawl via HeroAbility); `tags` classify the card.
class Hero
  attr_reader :id, :name, :health, :preferred_bait, :courage, :tags, :ability_text

  def initialize(id:, name:, health:, preferred_bait:, courage: 1, tags: [], ability_text: "")
    @id = id
    @name = name
    @health = Integer(health)
    @preferred_bait = Bait.normalize(preferred_bait)
    @courage = Integer(courage)
    @tags = tags.is_a?(Tags) ? tags : Tags.new(tags)
    @ability_text = ability_text.to_s
    freeze
  end
end
