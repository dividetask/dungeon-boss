# frozen_string_literal: true

# Interprets an ability card's declarative `effect` spec from cards.yaml. As
# with boss and room effects, the behaviour is data. The shape (each optional):
#
#   effect:
#     add_damage: 2        # +N to the targeted room this crawl (Reinforcements)
#     unreducible: true    # the targeted room's damage cannot be reduced (Expose Weakness)
#     zero: true           # the targeted room deals 0 this crawl (Sabotage)
#     retreat: true        # the party turns back at the targeted room (Retreat):
#                          #   rooms before it still resolve, the rest are skipped
#     draw_rooms: 2        # the player draws N room cards (Blueprints)
#
# `add_damage`, `unreducible`, `zero`, and `retreat` act on a chosen room, so
# those cards need a room target; `draw_rooms` does not.
module AbilityEffect
  class Spec
    def initialize(raw = {})
      raw = raw.is_a?(Hash) ? raw : {}
      @add_damage  = raw["add_damage"] && Integer(raw["add_damage"])
      @unreducible = raw["unreducible"] ? true : false
      @zero        = raw["zero"] ? true : false
      @retreat     = raw["retreat"] ? true : false
      @draw_rooms  = raw["draw_rooms"] && Integer(raw["draw_rooms"])
    end

    attr_reader :add_damage, :draw_rooms

    def unreducible?
      @unreducible
    end

    def zero?
      @zero
    end

    def retreat?
      @retreat
    end

    # Whether this card must be played on a specific room.
    def targets_room?
      !@add_damage.nil? || @unreducible || @zero || @retreat
    end
  end

  def self.for(card)
    Spec.new(card.respond_to?(:effect) ? card.effect : {})
  end
end
