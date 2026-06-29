# frozen_string_literal: true

require_relative "test_helper"

class HeroAbilityTest < Minitest::Test
  def hero(id)
    Hero.new(id: id, name: id, health: 10, preferred_bait: :glory)
  end

  def undead_room(damage)
    Room.new(id: "u", name: "U", type: "Basic Monster", damage: damage, bait: { undead: 1 })
  end

  def trap_room(damage)
    Room.new(id: "t", name: "T", type: "Basic Trap", damage: damage, bait: { glory: 1 })
  end

  def boss(damage)
    Boss.new(id: "b", name: "B", damage: damage, bait: { undead: 1 })
  end

  def test_barbarian_halves_all_damage_rounded_up
    ability = HeroAbility.lookup(hero("hero_barbarian"))

    assert_equal 3, ability.reduced(trap_room(5), 5) # ceil(5/2)
    assert_equal 1, ability.reduced(trap_room(1), 1)
    assert_equal 4, ability.reduced(boss(7), 7)       # applies to the boss too
  end

  def test_cleric_reduces_undead_damage_by_four_including_the_boss
    ability = HeroAbility.lookup(hero("hero_cleric"))

    assert_equal 0, ability.reduced(undead_room(3), 3) # 3 - 4, floored at 0
    assert_equal 1, ability.reduced(undead_room(5), 5)
    assert_equal 5, ability.reduced(trap_room(5), 5)   # not undead -> unchanged
    assert_equal 3, ability.reduced(boss(7), 7)        # undead boss IS reduced
  end

  def test_rogue_reduces_trap_damage_by_two
    ability = HeroAbility.lookup(hero("hero_rogue"))

    assert_equal 3, ability.reduced(trap_room(5), 5)
    assert_equal 1, ability.reduced(undead_room(1), 1) # monster room unchanged
  end

  def test_unknown_hero_takes_full_damage
    ability = HeroAbility.lookup(hero("hero_unknown"))

    assert_equal 5, ability.reduced(trap_room(5), 5)
  end

  # --- party-wide auras vs. self-only barbarian ---

  def test_cleric_aura_protects_a_different_hero
    cleric = hero("hero_cleric")
    other = hero("hero_unknown")
    # Other hero is the target, but the cleric is alive in the party.
    assert_equal 1, HeroAbility.damage_taken(other, undead_room(5), [cleric, other])
  end

  def test_rogue_aura_protects_a_different_hero_from_traps
    rogue = hero("hero_rogue")
    other = hero("hero_unknown")
    assert_equal 3, HeroAbility.damage_taken(other, trap_room(5), [rogue, other])
  end

  def test_dead_aura_hero_no_longer_protects
    other = hero("hero_unknown")
    # Cleric not in the alive list -> no reduction.
    assert_equal 5, HeroAbility.damage_taken(other, undead_room(5), [other])
  end

  def test_barbarian_only_reduces_its_own_damage
    barb = hero("hero_barbarian")
    other = hero("hero_unknown")

    # Barbarian alive but a different hero is hit: no halving.
    assert_equal 6, HeroAbility.damage_taken(other, trap_room(6), [barb, other])
    # Barbarian itself is hit: halved.
    assert_equal 3, HeroAbility.damage_taken(barb, trap_room(6), [barb, other])
  end

  def test_party_auras_and_self_halving_stack
    cleric = hero("hero_cleric")
    barb = hero("hero_barbarian")
    # Undead room dmg 6: cleric aura -4 -> 2, then barbarian self-halve -> 1.
    assert_equal 1, HeroAbility.damage_taken(barb, undead_room(6), [cleric, barb])
  end

  def test_crawl_resolver_applies_the_ability
    dungeon = Dungeon.new(boss(0))
    dungeon.add_room_to_left(undead_room(3)) # cleric reduces to 0

    cleric = Hero.new(id: "hero_cleric", name: "Cleric", health: 5, preferred_bait: :undead)
    result = CrawlResolver.resolve(cleric, dungeon)

    assert_equal :escaped, result.outcome
    assert_equal 5, result.remaining_health # took no damage
  end
end
