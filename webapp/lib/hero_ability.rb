# frozen_string_literal: true

require_relative "room"

# Hero special abilities: damage modifiers applied to each encounter during the
# crawl. Each ability responds to `reduced(encounter, damage)` (the damage taken,
# never below zero) and has a `scope`:
#   :party — protects whoever takes the hit, as long as this hero is alive in the
#            party (Cleric, Mage, Rogue).
#   :self  — only reduces damage this hero personally takes (Barbarian).
#
# Looked up by hero id via HeroAbility.lookup; heroes without an entry take full
# damage (the null ability). Use HeroAbility.damage_taken to combine a party's
# abilities for a single hit.
module HeroAbility
  # No reduction.
  class Null
    def scope = :self

    def reduced(_encounter, damage)
      damage
    end
  end

  # Halve all damage, rounded up — self only (Barbarian).
  class HalveRoundedUp
    def scope = :self

    def reduced(_encounter, damage)
      (damage / 2.0).ceil
    end
  end

  # Reduce damage from any encounter (room OR boss) carrying a given bait by a
  # fixed amount, for the whole party (Cleric: undead −4, Mage: power −4).
  class ReduceBaitDamage
    def initialize(bait, amount)
      @bait = bait
      @amount = amount
    end

    def scope = :party

    def reduced(encounter, damage)
      return damage unless encounter.bait.count(@bait).positive?

      [damage - @amount, 0].max
    end
  end

  # Reduce damage from trap-type rooms by a fixed amount, for the whole party
  # (Rogue: −2).
  class ReduceTrapDamage
    def initialize(amount)
      @amount = amount
    end

    def scope = :party

    def reduced(encounter, damage)
      return damage unless encounter.respond_to?(:type) && encounter.type.downcase.include?("trap")

      [damage - @amount, 0].max
    end
  end

  NULL = Null.new

  REGISTRY = {
    "hero_barbarian" => HalveRoundedUp.new,
    "hero_cleric"    => ReduceBaitDamage.new(Bait::UNDEAD, 4),
    "hero_mage"      => ReduceBaitDamage.new(Bait::POWER, 4),
    "hero_rogue"     => ReduceTrapDamage.new(2)
  }.freeze

  # The ability for a hero, or the null ability if it has none.
  def self.lookup(hero)
    REGISTRY.fetch(hero.id, NULL)
  end

  # Damage a target hero actually takes from an encounter, given the heroes still
  # alive in its party. Every alive member's :party aura applies; the target's
  # own :self ability applies last. `base` defaults to the encounter's printed
  # damage but can be overridden (e.g. a boss's points bonus).
  def self.damage_taken(target, encounter, alive_members, base = encounter.damage)
    damage = base
    alive_members.each do |member|
      ability = lookup(member)
      damage = ability.reduced(encounter, damage) if ability.scope == :party
    end
    own = lookup(target)
    damage = own.reduced(encounter, damage) if own.scope == :self
    damage
  end
end
