# frozen_string_literal: true

require_relative "bait"

# Shared building blocks for the declarative effects defined in data/cards.yaml.
# Effects are plain data — what they target and how much they add live in the
# YAML, so both clients read the same definition rather than reimplementing
# behaviour in code.
module Effects
  # A selector: a card (room/boss) or hero matches when it satisfies every
  # dimension that is present. Dimensions:
  #   tag            - the card carries this tag
  #   type           - "trap" or "creature"/"monster" (by the room's type)
  #   bait           - the card has at least one icon of this bait type
  #   preferred_bait - the hero's preferred bait (used to match heroes)
  class Selector
    def initialize(raw = {})
      raw = raw.is_a?(Hash) ? raw : {}
      @tag = raw["tag"]
      @type = raw["type"]
      @bait = raw["bait"]
      @preferred_bait = raw["preferred_bait"]
    end

    def matches?(card)
      return false if @tag && !tagged?(card)
      return false if @type && !type_matches?(card)
      return false if @bait && !has_bait?(card)
      return false if @preferred_bait && !prefers?(card)

      true
    end

    private

    def tagged?(card)
      card.respond_to?(:tags) && card.tags.include?(@tag)
    end

    def has_bait?(card)
      card.respond_to?(:bait) && card.bait.count(@bait).positive?
    end

    def prefers?(card)
      card.respond_to?(:preferred_bait) && card.preferred_bait == Bait.normalize(@preferred_bait)
    end

    def type_matches?(card)
      case @type.to_s.downcase
      when "trap"                then card.respond_to?(:trap?) && card.trap?
      when "creature", "monster" then card.respond_to?(:creature?) && card.creature?
      else false
      end
    end
  end

  # A damage aura: +`flat` and/or +`per_point × points` to every card that
  # matches the selector.
  class Aura
    def initialize(raw = {})
      raw = raw.is_a?(Hash) ? raw : {}
      @match = Selector.new(raw["match"])
      @flat = Integer(raw["flat"] || 0)
      @per_point = Integer(raw["per_point"] || 0)
    end

    def bonus(target, points)
      return 0 unless @match.matches?(target)

      @flat + @per_point * points
    end
  end
end
