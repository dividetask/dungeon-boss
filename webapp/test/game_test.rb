# frozen_string_literal: true

require_relative "test_helper"

# End-to-end smoke test: load the real card data and drive the game through its
# decisions, choosing the first available option each time (the player choice
# always happens via the decision loop — nothing is auto-picked by the engine).
class GameTest < Minitest::Test
  def setup
    @library = CardLibrary.load(CARDS_PATH)
    @game = Game.new(@library, ["A", "B"], rng: Random.new(1234)).start
  end

  # Resolve every pending decision automatically, taking a valid choice for each
  # (the live hand for room decisions, since the hand changes during Build).
  def resolve_all_decisions
    while (decision = @game.current_decision)
      case decision.kind
      when :discard_room
        @game.decide(decision.player.room_hand.first.id)
      when :build_room
        # Auto-driver keeps it simple: place a basic room, else build nothing.
        basic = decision.player.room_hand.find { |c| c.is_a?(Room) && !c.advanced? }
        if basic
          @game.decide(basic.id, decision.player.dungeon.full? ? 0 : "new")
        else
          @game.decide(nil)
        end
      else
        @game.decide(decision.options.first&.id)
      end
    end
  end

  # Resolve all decisions, send every entering party, and finish a quiet round.
  def finish_round
    resolve_all_decisions
    @game.send_next_party while @game.crawling?
    @game.finish_quiet_round if @game.quiet?
  end

  # Total living heroes across all parties in town.
  def heroes_in_town
    @game.town.sum(&:size)
  end

  def test_supports_up_to_four_players_with_one_hero_arrival_each
    (2..4).each do |n|
      names = (1..n).map { |i| "P#{i}" }
      agents = names.drop(1).to_h { |name| [name, RandomAgent.new(Random.new(1))] }
      g = Game.new(@library, names, rng: Random.new(5), agents: agents).start

      assert_equal n, g.players.size
      # Player 1 is human; the rest are computer-controlled.
      refute g.automated?(g.players.first)
      g.players.drop(1).each { |p| assert g.automated?(p) }

      # Finish setup (only the human still has pending decisions), then a round.
      g.decide(g.current_decision.options.first.id) until g.current_decision.nil? || g.ready?
      g.start_round if g.ready?

      # One hero arrives per player, so town holds exactly `n` after round 1.
      assert_equal n, g.town.sum(&:size), "#{n} players should yield #{n} arrivals"
    end
  end

  def test_eliminated_players_are_dropped_from_the_round
    names = %w[P1 P2 P3 P4]
    agents = names.drop(1).to_h { |name| [name, RandomAgent.new(Random.new(1))] }
    g = Game.new(@library, names, rng: Random.new(2), agents: agents).start
    g.decide(g.current_decision.options.first.id) until g.ready? # finish setup

    # Eliminate the human (P1) and one computer (P3) with 5 wounds each.
    human, _, p3, = g.players
    5.times { human.gain_wound }
    5.times { p3.gain_wound }
    assert g.eliminated?(human)
    assert_equal 2, g.living_players.size # P2 and P4 remain

    town_before = g.town.sum(&:size)
    g.start_round

    # Arrivals equal the number of LIVING players (2), not all four.
    assert_equal town_before + 2, g.town.sum(&:size)

    # The eliminated human is never asked to decide (no discard/build prompt).
    while (d = g.current_decision)
      refute_equal human, d.player, "eliminated player should get no decisions"
      g.decide(d.kind == :build_room ? nil : d.options.first&.id)
    end
  end

  def test_discard_boost_rooms_add_four
    boost_rooms = @library.advanced_rooms.select { |r| RoomEffect.for(r).discard_boost&.key?("add_damage") }
    refute_empty boost_rooms # Collapsing Tunnel / Golem / Necrotic Fog
    boost_rooms.each { |r| assert_equal 4, RoomEffect.for(r).discard_boost["add_damage"] }
  end

  def test_quiet_round_lets_the_human_play_blueprints_then_continue
    g = Game.new(@library, %w[A B], rng: Random.new(1)).start
    g.decide(g.current_decision.options.first&.id) until g.ready? # finish setup

    a, b = g.players
    # No bait anywhere -> every party is unenticed (tie at 0) -> no one attacks.
    a.dungeon = Dungeon.new(Boss.new(id: "b1", name: "B", damage: 1, bait: {}))
    b.dungeon = Dungeon.new(Boss.new(id: "b2", name: "B2", damage: 1, bait: {}))
    g.instance_variable_set(:@town, [Party.new([Hero.new(id: "h", name: "H", health: 5, preferred_bait: :glory, courage: 1)])])
    g.instance_variable_set(:@stage, :building)
    g.instance_variable_set(:@decisions, [])
    g.send(:resolve_if_idle)

    assert g.quiet? # no hero attacked
    refute g.crawling?

    blueprints = @library.ability_cards.find { |c| AbilityEffect.for(c).draw_rooms }
    a.add_ability_to_hand(blueprints)
    rooms_before = a.room_hand.size
    g.play_ability(a, blueprints.id)
    assert_operator a.room_hand.size, :>, rooms_before # drew rooms with no crawl

    abilities_before = a.ability_hand.size
    g.finish_quiet_round
    assert g.ready?
    assert_equal abilities_before + 1, a.ability_hand.size # quiet-round ability draw
  end

  def test_courage_is_rechecked_per_party_as_points_rise_mid_turn
    g = Game.new(@library, %w[A B], rng: Random.new(1)).start
    g.decide(g.current_decision.options.first&.id) until g.ready? # finish setup

    a, b = g.players
    a.dungeon = Dungeon.new(Boss.new(id: "lethal", name: "B", damage: 99, bait: { glory: 9 }))
    a.points = 1
    b.dungeon = Dungeon.new(Boss.new(id: "dull", name: "B2", damage: 0, bait: {}))
    b.points = 0

    glory = -> { Party.new([Hero.new(id: "g", name: "Glory", health: 1, preferred_bait: :glory, courage: 1)]) }
    party1 = glory.call
    party2 = glory.call
    g.instance_variable_set(:@town, [party1, party2])

    # Enter the combined bait+crawl flow directly (skipping arrival/build).
    g.instance_variable_set(:@stage, :building)
    g.instance_variable_set(:@decisions, [])
    g.send(:resolve_if_idle)

    # Party 1 enters A's dungeon (courage 1 >= 1 point) and dies, scoring a point.
    assert g.crawling?
    assert_equal a, g.next_crawl.first
    g.send_next_party

    # A now has 2 points; party 2 (courage 1) is too timid, so no second crawl.
    assert_equal 2, a.points
    refute g.crawling?
    assert_equal 1, g.last_outcomes.size
  end

  def test_starting_hand_always_contains_a_room
    10.times do |seed|
      g = Game.new(@library, %w[A B], rng: Random.new(seed)).start
      g.players.each do |p|
        assert(p.room_hand.any? { |c| c.is_a?(Room) }, "seed #{seed}: hand had no room")
      end
    end
  end

  def test_draw_rooms_ability_draws_two_cards
    # Drive to a crawl (the pre-crawl window where abilities are played).
    resolve_all_decisions
    @game.start_round
    resolve_all_decisions
    skip "no crawl this seed" unless @game.crawling?

    human = @game.players.first
    blueprints = @library.ability_cards.find { |a| AbilityEffect.for(a).draw_rooms }
    human.add_ability_to_hand(blueprints)
    rooms_before = human.room_hand.size
    abilities_before = human.ability_hand.size

    @game.play_ability(human, blueprints.id)

    assert_equal [rooms_before + 2, Player::MAX_ROOM_HAND].min, human.room_hand.size
    assert_equal abilities_before - 1, human.ability_hand.size # the card was discarded
  end

  def test_death_draws_add_cards_to_the_owners_hand
    player = @game.players.first
    abilities_before = player.ability_hand.size
    rooms_before = player.room_hand.size
    result = Struct.new(:draws).new({ "ability" => 1, "room" => 1 })

    @game.send(:apply_death_draws, player, result)

    assert_equal abilities_before + 1, player.ability_hand.size
    assert_equal [rooms_before + 1, Player::MAX_ROOM_HAND].min, player.room_hand.size
  end

  def test_players_start_with_two_ability_cards
    resolve_all_decisions
    @game.players.each { |p| assert_equal 2, p.ability_hand.size }
  end

  def test_player_chooses_boss_from_two_candidates
    decision = @game.current_decision

    assert_equal :choose_boss, decision.kind
    assert_equal 2, decision.options.size
  end

  def test_setup_gives_each_player_a_boss_and_one_room
    resolve_all_decisions

    @game.players.each do |player|
      refute_nil player.dungeon.boss
      assert_equal 1, player.dungeon.rooms.size
      assert_equal 3, player.room_hand.size # 4 dealt, 1 placed
    end
    assert @game.ready?
  end

  def test_playing_a_round_keeps_survivors_in_town
    resolve_all_decisions # finish setup
    @game.start_round
    finish_round

    assert_equal 1, @game.round
    assert @game.ready?
    # Two heroes arrived this turn; a hero only leaves town by dying, and every
    # death scores a point. So living heroes == arrivals - deaths == 2 - points.
    deaths = @game.players.sum(&:points)
    assert_equal 2 - deaths, heroes_in_town
  end

  def test_heroes_accumulate_in_town_across_turns
    resolve_all_decisions
    3.times do
      break unless @game.ready?

      @game.start_round
      finish_round
    end

    # 2 heroes arrive per turn; only deaths remove a hero (merging keeps them in
    # town), and each death scores a point.
    deaths = @game.players.sum(&:points)
    assert_equal 2 * @game.round - deaths, heroes_in_town
  end

  def test_hand_never_exceeds_cap
    resolve_all_decisions
    6.times do
      break unless @game.ready?

      @game.start_round
      finish_round
      @game.players.each { |p| assert_operator p.room_hand.size, :<=, Player::MAX_ROOM_HAND }
    end
  end

  def test_points_and_wounds_only_grow
    resolve_all_decisions
    before = @game.players.map { |p| [p.points, p.wounds] }

    3.times do
      break unless @game.ready?

      @game.start_round
      finish_round
    end
    after = @game.players.map { |p| [p.points, p.wounds] }

    before.zip(after).each do |(bp, bw), (ap, aw)|
      assert_operator ap, :>=, bp
      assert_operator aw, :>=, bw
    end
  end

  def test_build_starts_with_a_mandatory_discard
    resolve_all_decisions
    @game.start_round

    decision = @game.current_decision
    assert_equal :discard_room, decision.kind
    refute decision.allow_skip
  end

  def test_build_can_replace_a_room_in_place
    resolve_all_decisions # finish setup; each player has exactly one room
    @game.start_round     # draws two rooms into each hand; queues discard + build

    player = @game.current_decision.player
    # Discard a non-basic-room if possible so a basic room remains to place
    # (a basic room can replace any room; an advanced one needs a shared bait).
    basic = player.room_hand.find { |c| c.is_a?(Room) && !c.advanced? }
    discard = player.room_hand.find { |c| c != basic } || player.room_hand.first
    @game.decide(discard.id) # mandatory discard

    original_room = player.dungeon.rooms.first.base_room
    replacement = player.room_hand.find { |c| c.is_a?(Room) && !c.advanced? }
    refute_nil replacement, "expected a basic room in hand to place"
    @game.decide(replacement.id, 0) # build: replace the room at index 0

    assert_equal 1, player.dungeon.rooms.size # replaced, not added
    assert_same replacement, player.dungeon.rooms.first.base_room
    refute_same original_room, player.dungeon.rooms.first.base_room
  end

  def test_undo_discard_returns_the_card_and_re_prompts
    resolve_all_decisions
    @game.start_round
    player = @game.current_decision.player # head is this player's mandatory discard

    discarded = player.room_hand.first
    hand_before = player.room_hand.map(&:id).sort
    @game.decide(discarded.id) # mandatory discard

    assert @game.can_undo_discard?
    assert_equal :build_room, @game.current_decision.kind

    @game.undo_discard

    refute @game.can_undo_discard? # window closed after undoing
    assert_equal :discard_room, @game.current_decision.kind
    assert_includes player.room_hand.map(&:id), discarded.id
    assert_equal hand_before, player.room_hand.map(&:id).sort # hand fully restored
  end

  def test_can_discard_again_after_undo
    resolve_all_decisions
    @game.start_round
    player = @game.current_decision.player
    @game.decide(player.room_hand.first.id) # discard
    @game.undo_discard                       # take it back

    # Discarding again must not raise (the re-prompt's options must not freeze
    # the player's live hand).
    size_before = player.room_hand.size
    @game.decide(player.room_hand.last.id)

    assert_equal size_before - 1, player.room_hand.size # a card was actually discarded
    assert_equal :build_room, @game.current_decision.kind
  end

  def test_cannot_undo_after_building
    resolve_all_decisions
    @game.start_round
    player = @game.current_decision.player
    @game.decide(player.room_hand.first.id) # discard
    @game.decide(nil)                        # build nothing -> closes the window

    refute @game.can_undo_discard?

    head_before = @game.current_decision
    @game.undo_discard # no-op: the window is closed
    assert_same head_before, @game.current_decision # queue unchanged
  end

  def test_skipping_a_build_leaves_the_dungeon_unchanged
    resolve_all_decisions
    @game.start_round
    player = @game.current_decision.player
    @game.decide(player.room_hand.first.id) # mandatory discard

    rooms_before = player.dungeon.rooms.size
    @game.decide(nil) # build nothing

    assert_equal rooms_before, player.dungeon.rooms.size
  end
end
