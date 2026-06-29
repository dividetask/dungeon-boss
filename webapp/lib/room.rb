# frozen_string_literal: true

require_relative "bait_icons"
require_relative "tags"

# One room card's data. Rooms make up the body of a dungeon.
# Immutable. `description` is flavor text. An "advanced" room may only be placed
# on an existing room sharing one of its bait icons; `effect` is the declarative
# crawl-effect spec (a Hash, interpreted by RoomEffect); `tags` classify the card.
class Room
  attr_reader :id, :name, :type, :damage, :bait, :description, :effect, :tags

  def initialize(id:, name:, type:, damage:, bait:, description: "", effect: nil, tags: [], advanced: false)
    @id = id
    @name = name
    @type = type.to_s
    @damage = Integer(damage)
    @bait = bait.is_a?(BaitIcons) ? bait : BaitIcons.new(bait)
    @description = description.to_s
    @effect = effect.is_a?(Hash) ? effect : {}
    @tags = tags.is_a?(Tags) ? tags : Tags.new(tags)
    @advanced = advanced ? true : false
    freeze
  end

  def advanced?
    @advanced
  end

  def trap?
    @type.downcase.include?("trap")
  end

  def creature?
    @type.downcase.match?(/monster|creature/)
  end
end
