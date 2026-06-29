# frozen_string_literal: true

require_relative "bait_icons"

# A room as it sits in a dungeon: a base Room plus at most one Upgrade. Because
# room cards are shared, immutable value objects, the per-slot upgrade and the
# permanent `grow` bonus (from grow-on-death effects) live here rather than on
# the Room. Exposes the *effective* damage and bait, so resolvers, bait counting,
# and the summary treat it like a room.
class PlacedRoom
  attr_reader :base_room
  attr_accessor :upgrade, :grow

  def initialize(base_room, upgrade: nil)
    @base_room = base_room
    @upgrade = upgrade
    @grow = 0
  end

  def id
    @base_room.id
  end

  def name
    @base_room.name
  end

  def type
    @base_room.type
  end

  def effect
    @base_room.effect
  end

  def description
    @base_room.description
  end

  def trap?
    @base_room.trap?
  end

  def creature?
    @base_room.creature?
  end

  # The base room's tags, plus any the attached upgrade contributes.
  def tags
    return @base_room.tags unless @upgrade

    @base_room.tags | @upgrade.tags
  end

  def damage
    @base_room.damage + (@upgrade ? @upgrade.bonus_damage : 0) + @grow
  end

  # Base bait plus the upgrade's added bait icons.
  def bait
    return @base_room.bait unless @upgrade

    totals = Hash.new(0)
    @base_room.bait.to_h.each { |bait, count| totals[bait] += count }
    @upgrade.bait.to_h.each { |bait, count| totals[bait] += count }
    BaitIcons.new(totals)
  end
end
