# frozen_string_literal: true

require_relative "effects"

# Interprets a room's declarative `effect` spec from cards.yaml. Like boss
# effects, the behaviour is data — nothing here hard-codes a particular room.
# The shape (every key optional):
#
#   effect:
#     room_auras:                       # +damage to OTHER matching rooms
#       - match: { type: trap }         #   (Trap Makers / Beast Tamer)
#         flat: 2
#     party_hits:                       # unreducible hits on matching members
#       - match: { preferred_bait: power }   #   (Antimagic / Zealots)
#         amount: 4
#     poisons_on_hit: true              # the hero this room damages is poisoned
#     grows_on_death: true              # +1 damage permanently per death here
#     discard_boost: { set_damage: 6 }  # owner may discard a card to set this
#     discard_boost: { unreducible: true } #   room to N / make it unreducible
#
# The resolver consults these hooks; an absent key means "no such behaviour".
module RoomEffect
  # One "hit each matching member for `amount` unreducible damage" rule.
  class PartyHit
    def initialize(raw = {})
      raw = raw.is_a?(Hash) ? raw : {}
      @match = Effects::Selector.new(raw["match"])
      @amount = Integer(raw["amount"] || 0)
    end

    def hits(alive)
      alive.select { |hero| @match.matches?(hero) }.map { |hero| [hero, @amount] }
    end
  end

  class Spec
    def initialize(raw = {})
      raw = raw.is_a?(Hash) ? raw : {}
      @auras          = Array(raw["room_auras"]).map { |a| Effects::Aura.new(a) }
      @party_hits     = Array(raw["party_hits"]).map { |h| PartyHit.new(h) }
      @poisons_on_hit = raw["poisons_on_hit"] ? true : false
      @grows_on_death = raw["grows_on_death"] ? true : false
      @unreducible    = raw["unreducible"] ? true : false
      @next_room_damage = Integer(raw["next_room_damage"] || 0)
      @draw_on_death  = raw["draw_on_death"] # "ability" | "room" | nil
      @discard_boost  = raw["discard_boost"].is_a?(Hash) ? raw["discard_boost"] : nil
    end

    # Extra damage this room grants to another room, for `points` owner points.
    def aura_bonus(target, points = 0)
      @auras.sum { |a| a.bonus(target, points) }
    end

    # [[hero, amount], ...] unreducible hits this room deals to party members.
    def party_hits(alive, _encounter = nil)
      @party_hits.flat_map { |h| h.hits(alive) }
    end

    def poisons_on_hit?
      @poisons_on_hit
    end

    def grows_on_death?
      @grows_on_death
    end

    # This room's own single-target damage cannot be reduced by hero abilities.
    def unreducible?
      @unreducible
    end

    # Damage the hero this room hits also takes when it enters the NEXT room
    # (a one-shot delayed hit), or 0 for none.
    def next_room_damage
      @next_room_damage
    end

    # When a hero dies in this room, the owner draws a card from this deck
    # ("ability" or "room"), or nil for none.
    def draws_on_death
      @draw_on_death
    end

    # Whether the owner may discard a room card to boost this room.
    def boostable?
      !@discard_boost.nil?
    end

    # The discard-to-boost spec ({"set_damage"=>n} or {"unreducible"=>true}), or nil.
    attr_reader :discard_boost
  end

  NULL = Spec.new

  # The parsed effect for an encounter. Rooms carry an `effect` map; a plain room
  # or the boss yields the do-nothing NULL spec.
  def self.for(encounter)
    return NULL unless encounter.respond_to?(:effect)

    Spec.new(encounter.effect)
  end
end
