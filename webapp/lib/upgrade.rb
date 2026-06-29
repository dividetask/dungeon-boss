# frozen_string_literal: true

require_relative "bait_icons"
require_relative "tags"

# An upgrade card. Drawn from the same deck as rooms (it is a room-card type),
# but instead of being placed as a room it is attached to an existing room to
# boost it. Immutable. An upgrade adds bait icons and/or bonus damage, and may
# carry `tags` that merge onto the room it upgrades.
class Upgrade
  TYPE = "Upgrade"

  attr_reader :id, :name, :bonus_damage, :bait, :description, :tags

  def initialize(id:, name:, bonus_damage: 0, bait: nil, description: "", tags: [])
    @id = id
    @name = name
    @bonus_damage = Integer(bonus_damage)
    @bait = bait.is_a?(BaitIcons) ? bait : BaitIcons.new(bait)
    @description = description.to_s
    @tags = tags.is_a?(Tags) ? tags : Tags.new(tags)
    freeze
  end

  def type
    TYPE
  end
end
