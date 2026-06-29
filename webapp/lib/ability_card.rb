# frozen_string_literal: true

require_relative "tags"

# One ability card's data. Held in hand and played to alter a crawl.
# `effect` is the declarative effect spec (a Hash, interpreted by AbilityEffect);
# `text` is the rules text shown to players; `tags` classify the card.
class AbilityCard
  attr_reader :id, :name, :text, :effect, :tags

  def initialize(id:, name:, text: "", effect: nil, tags: [])
    @id = id
    @name = name
    @text = text.to_s
    @effect = effect.is_a?(Hash) ? effect : {}
    @tags = tags.is_a?(Tags) ? tags : Tags.new(tags)
    freeze
  end
end
