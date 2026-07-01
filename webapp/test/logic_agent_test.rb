# frozen_string_literal: true

require_relative "test_helper"

class LogicAgentTest < Minitest::Test
  def agent(logic)
    LogicAgent.new(logic, rng: Random.new(1))
  end

  def boss(id, damage, bait = {})
    Boss.new(id: id, name: id, damage: damage, bait: bait)
  end

  def room(id, damage, bait = {})
    Room.new(id: id, name: id, type: "trap", damage: damage, bait: bait)
  end

  # --- static comparators -------------------------------------------------

  def test_choose_boss_prefers_highest_damage
    logic = { "choose_boss" => [{ "prefer" => "highest_damage" }] }
    decision = Decision.new(kind: :choose_boss, player: Player.new("X"),
                            options: [boss("weak", 2), boss("strong", 7)])

    assert_equal ["strong", nil], agent(logic).choose(decision)
  end

  def test_discard_prefers_lowest_damage
    player = Player.new("X")
    player.add_room_to_hand(room("keep", 6))
    player.add_room_to_hand(room("toss", 1))
    logic = { "discard_room" => [{ "prefer" => "lowest_damage" }] }
    decision = Decision.new(kind: :discard_room, player: player, options: player.room_hand)

    assert_equal ["toss", nil], agent(logic).choose(decision)
  end

  def test_tie_break_chain_falls_through_to_next_rule
    # Two bosses tie on damage; the bait-priority rule breaks it toward glory.
    logic = { "choose_boss" => [{ "prefer" => "highest_damage" },
                                { "prefer" => %w[glory riches] }] }
    decision = Decision.new(kind: :choose_boss, player: Player.new("X"),
                            options: [boss("riches_boss", 5, riches: 2),
                                      boss("glory_boss", 5, glory: 1)])

    assert_equal ["glory_boss", nil], agent(logic).choose(decision)
  end

  def test_unknown_comparator_is_rejected
    logic = { "choose_boss" => [{ "prefer" => "nonsense" }] }
    decision = Decision.new(kind: :choose_boss, player: Player.new("X"),
                            options: [boss("a", 1), boss("b", 2)])

    assert_raises(ArgumentError) { agent(logic).choose(decision) }
  end

  # --- build_room moves + simulation -------------------------------------

  # A build decision where the player holds one strong and one weak room and has
  # an empty dungeon slot to add to.
  def build_decision(hand_damages)
    player = Player.new("X")
    player.dungeon = Dungeon.new(boss("boss", 0))
    player.dungeon.add_room_to_left(room("existing", 1))
    hand_damages.each_with_index { |dmg, i| player.add_room_to_hand(room("hand#{i}_#{dmg}", dmg)) }
    Decision.new(kind: :build_room, player: player, options: player.room_hand, allow_skip: true)
  end

  def test_build_can_skip_when_no_legal_move_helps
    # With no rules and an empty hand, the only candidate is "build nothing".
    decision = build_decision([])

    assert_equal [nil, nil], agent({}).choose(decision)
  end

  def test_build_simulation_prefers_the_move_that_kills_heroes
    decision = build_decision([8, 1]) # one lethal room, one feeble room
    a = LogicAgent.new({ "build_room" => [{ "prefer" => "most_points" }] }, rng: Random.new(1))
    a.attach(stub_game([Party.new([Hero.new(id: "h", name: "H", health: 5, preferred_bait: :glory)])]))

    choice_id, target = a.choose(decision)

    # It should place the 8-damage room (lethal to the 5-health hero), not the 1.
    assert_equal "hand0_8", choice_id
    refute_nil target
  end

  def test_build_without_a_game_still_answers_via_static_fallback
    decision = build_decision([9, 2])
    # No town attached: simulation scores 0 for all, so highest_damage decides.
    a = LogicAgent.new({ "build_room" => [{ "prefer" => "most_points" },
                                          { "prefer" => "highest_damage" }] }, rng: Random.new(1))

    choice_id, = a.choose(decision)
    assert_equal "hand0_9", choice_id
  end

  # Minimal stand-in for Game: LogicAgent only reads #town for simulations.
  def stub_game(town)
    Object.new.tap do |g|
      g.define_singleton_method(:town) { town }
    end
  end
end
