# frozen_string_literal: true

require_relative "test_helper"

class RandomAgentTest < Minitest::Test
  def setup
    @library = CardLibrary.load(CARDS_PATH)
  end

  def boss_a
    Boss.new(id: "b1", name: "B1", damage: 1, bait: {})
  end

  def test_chooses_one_of_the_options
    agent = RandomAgent.new(Random.new(1))
    decision = Decision.new(kind: :choose_boss, player: Player.new("X"),
                            options: [boss_a])

    choice_id, target = agent.choose(decision)
    assert_equal "b1", choice_id
    assert_nil target
  end

  def test_may_skip_when_allowed
    agent = RandomAgent.new(Random.new(1))
    decision = Decision.new(kind: :build_room, player: Player.new("X"),
                            options: [], allow_skip: true)

    choice_id, = agent.choose(decision)
    assert_nil choice_id # only option is to skip
  end

  def test_automated_player_decisions_never_surface
    game = Game.new(@library, ["Human", "Bot"], rng: Random.new(7),
                    agents: { "Bot" => RandomAgent.new(Random.new(7)) }).start

    # Every decision the game presents belongs to the human, never the bot.
    while (decision = game.current_decision)
      assert_equal "Human", decision.player.name
      game.decide(decision.options.first&.id)
    end

    # The bot still ended up with a fully set-up dungeon.
    bot = game.players.find { |p| p.name == "Bot" }
    refute_nil bot.dungeon.boss
    assert_equal 1, bot.dungeon.rooms.size
    assert game.automated?(bot)
  end
end
