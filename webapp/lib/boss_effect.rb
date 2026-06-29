# frozen_string_literal: true

require_relative "effects"

# Interprets a boss's declarative `effect` spec from cards.yaml. The bonus
# amounts and what they target live in the YAML, not in code, so both clients
# read the same definition. The shape:
#
#   effect:
#     self_damage_per_point: 3        # boss's own bonus per owner point (default 1)
#     room_bonuses:                   # auras granted to matching rooms
#       - match: { tag: goblin }
#         per_point: 1
#       - match: { type: trap }
#         flat: 2
#
# Every boss deals +`self_damage_per_point` damage per point it has scored
# (default 1). `room_bonuses` add flat and/or per-point damage to each matching
# room; the boss's own attack is never a room bonus.
module BossEffect
  class Spec
    def initialize(raw = {})
      raw = raw.is_a?(Hash) ? raw : {}
      @self_per_point = Integer(raw["self_damage_per_point"] || 1)
      @room_bonuses   = Array(raw["room_bonuses"]).map { |b| Effects::Aura.new(b) }
    end

    # The boss's own bonus damage for `points` owner points.
    def self_bonus(points)
      @self_per_point * points
    end

    # Total bonus damage this boss grants to one room, for `points` owner points.
    def room_bonus(room, points)
      @room_bonuses.sum { |b| b.bonus(room, points) }
    end
  end

  # The parsed effect for a boss (defaults to "+1 per point, no room bonuses").
  def self.for(boss)
    Spec.new(boss.respond_to?(:effect) ? boss.effect : {})
  end
end
