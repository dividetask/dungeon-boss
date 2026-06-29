# frozen_string_literal: true

require_relative "test_helper"

# Ability effects are declarative data; AbilityEffect interprets the spec.
class AbilityEffectTest < Minitest::Test
  def spec(raw)
    AbilityEffect.for(Struct.new(:effect).new(raw))
  end

  def test_add_damage_targets_a_room
    s = spec("add_damage" => 2)
    assert_equal 2, s.add_damage
    assert s.targets_room?
    refute s.retreat?
  end

  def test_unreducible_zero_and_retreat_target_a_room
    assert spec("unreducible" => true).targets_room?
    assert spec("zero" => true).zero?
    assert spec("zero" => true).targets_room?
    assert spec("retreat" => true).retreat?
    assert spec("retreat" => true).targets_room? # Retreat now picks a room
  end

  def test_draw_does_not_target_a_room
    assert_equal 2, spec("draw_rooms" => 2).draw_rooms
    refute spec("draw_rooms" => 2).targets_room?
  end

  def test_empty_effect_does_nothing
    s = spec(nil)
    assert_nil s.add_damage
    assert_nil s.draw_rooms
    refute s.targets_room?
    refute s.retreat?
  end

  # The five real ability cards interpret to the expected behaviour.
  def test_real_ability_cards
    lib = CardLibrary.load(CARDS_PATH)
    by_name = lib.ability_cards.to_h { |c| [c.name, AbilityEffect.for(c)] }

    assert_equal 4, by_name["Reinforcements"].add_damage
    assert by_name["Expose Weakness"].unreducible?
    assert by_name["Sabotage"].zero?
    assert by_name["Retreat"].retreat?
    assert_equal 2, by_name["Blueprints"].draw_rooms
  end
end
