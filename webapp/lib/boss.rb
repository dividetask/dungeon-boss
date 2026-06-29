# frozen_string_literal: true

require_relative "bait_icons"
require_relative "tags"

# One boss card's data. The back of a dungeon and the final crawl encounter.
# Immutable. `effect` is the declarative effect spec (a Hash from cards.yaml,
# interpreted by BossEffect); `tags` classify the card; `ability_text` is flavor.
class Boss
  attr_reader :id, :name, :damage, :bait, :effect, :tags, :ability_text

  def initialize(id:, name:, damage:, bait:, effect: nil, tags: [], ability_text: "")
    @id = id
    @name = name
    @damage = Integer(damage)
    @bait = bait.is_a?(BaitIcons) ? bait : BaitIcons.new(bait)
    @effect = effect.is_a?(Hash) ? effect : {}
    @tags = tags.is_a?(Tags) ? tags : Tags.new(tags)
    @ability_text = ability_text.to_s
    freeze
  end
end
