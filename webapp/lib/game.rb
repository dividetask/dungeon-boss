# frozen_string_literal: true

require_relative "deck"
require_relative "room"
require_relative "player"
require_relative "party"
require_relative "scoreboard"
require_relative "crawl_modifiers"
require_relative "room_effect"
require_relative "ability_effect"
require_relative "decision"
require_relative "crawl_log"
require_relative "phases/setup_phase"
require_relative "phases/arrival_phase"
require_relative "phases/build_phase"
require_relative "phases/bait_phase"
require_relative "phases/crawl_phase"
require_relative "phases/recruitment_phase"

# Owns the players and decks and drives the turn as a sequence of player
# decisions. The player makes every choice the rules call for (which boss to
# keep, which room to place); the automatic phases (Arrival, Bait, Crawl) run on
# their own. Orchestration only — all rules live in the phase/resolver classes.
class Game
  attr_reader :players, :boss_deck, :room_deck, :hero_deck, :ability_deck,
              :town, :round, :last_outcomes, :stage

  # @param library [CardLibrary] the loaded card pools
  # @param player_names [Array<String>]
  # @param rng [Random] optional, for deterministic shuffles
  # @param agents [Hash{String=>#choose}] map of player name => automated agent;
  #   decisions for those players are resolved by the agent, not surfaced.
  attr_reader :rng

  def initialize(library, player_names, rng: Random.new, agents: {}, log: nil)
    @rng = rng
    @log = log
    @players = player_names.map { |name| Player.new(name) }
    @boss_deck = Deck.new(library.bosses, rng: rng).shuffle!
    # Rooms, upgrades, and advanced rooms share one build deck.
    @room_deck = Deck.new(library.rooms + library.upgrades + library.advanced_rooms, rng: rng).shuffle!
    @hero_deck = Deck.new(library.heroes, rng: rng).shuffle!
    @ability_deck = Deck.new(library.ability_cards, rng: rng).shuffle!
    @agents = build_agents(agents)
    @town = []                # list of Party (a lone hero is a party of one)
    @round = 0
    @last_outcomes = []
    @winner = nil
    @decisions = []          # queue of pending player Decisions
    @crawl_queue = []         # parties still to be evaluated for entry this turn
    @current_crawl = nil      # [player, party] now in the pre-crawl window, or nil
    @any_entered = false      # did any party enter a dungeon this turn?
    @crawl_mods = CrawlModifiers.new # per-crawl ability/boost modifiers
    @waiting_parties = []     # parties that did not enter this turn (for Recruitment)
    @undoable_discard = nil   # [player, card] the most recent build-phase discard
    @boss_candidates = {}     # player => [boss, boss] during Setup
    @stage = :unstarted       # :unstarted -> :setup -> :ready <-> :building -> :crawling
  end

  # Begin the game: deal hands and queue each player's Setup decisions.
  def start
    @log&.game_started(@players.map(&:name))
    SetupPhase.deal(self)
    @players.each { |p| enqueue(:choose_boss, p, boss_candidates(p)) }
    # Only basic rooms can be the first placed room (not upgrades or advanced).
    @players.each { |p| enqueue(:place_first_room, p, p.room_hand.select { |c| c.is_a?(Room) && !c.advanced? }, mandatory: true) }
    @stage = :setup
    auto_advance
    self
  end

  # Begin one round of play. Only valid once setup (and any prior round) is
  # resolved. Arrival runs automatically; Build queues a decision per player.
  def start_round
    raise "not ready to start a round" unless ready?

    @round += 1
    @last_outcomes = [] # clear last turn's crawl so it doesn't linger on screen
    ArrivalPhase.run(self)
    BuildPhase.draw_for_each(self)
    # Eliminated players (5+ wounds) are out: they take no build turn.
    living_players.each do |p|
      enqueue(:discard_room, p, p.room_hand, mandatory: true)
      enqueue(:build_room, p, p.room_hand, allow_skip: true)
    end
    @stage = :building
    auto_advance
    self
  end

  # Send the current party into its dungeon (one crawl per call), then work out
  # which party (if any) enters next — re-checking courage against each owner's
  # now-current points, so a death that scored a point can scare off the next
  # party. Just before resolving, the owner's agent takes its pre-crawl actions
  # (discard-to-boost) and the accumulated CrawlModifiers are applied.
  def send_next_party
    return self if @current_crawl.nil?

    player, party = @current_crawl
    agent_pre_crawl(player)
    outcome = CrawlPhase.resolve_party(self, player, party, @crawl_mods)
    @last_outcomes = [outcome]
    apply_death_draws(player, outcome.result)
    @log&.record(@round, outcome)
    @current_crawl = nil

    if Scoreboard.over?(@players)
      @winner = Scoreboard.winner(@players, player) # the crawl's owner ended the game
      @crawl_queue = []
      @stage = :over
    else
      advance_to_next_crawl
    end
    self
  end

  # Modifiers (ability cards / discard-to-boost) being assembled for the party
  # currently at the front of the crawl queue.
  attr_reader :crawl_mods

  # Play an ability card from `player`'s hand on the current crawl.
  #   target: a room index in the crawled dungeon, or nil for party-targeting.
  def play_ability(player, card_id, target = nil)
    return self unless @current_crawl || quiet?

    card = player.ability_hand.find { |c| c.id == card_id }
    return self unless card

    spec = AbilityEffect.for(card)
    # On a quiet round there is no crawl, so only no-attack abilities (Blueprints)
    # can be played; room-targeting cards do nothing and are not spent.
    return self if @current_crawl.nil? && spec.targets_room?

    player.take_ability_from_hand(card_id)
    if @current_crawl && (room = target && !target.to_s.empty? ? Integer(target) : nil)
      @crawl_mods.add_damage(room, spec.add_damage) if spec.add_damage
      @crawl_mods.unreducible!(room) if spec.unreducible?
      @crawl_mods.zero!(room) if spec.zero?
      @crawl_mods.retreat!(room) if spec.retreat?
    end
    draw_rooms_for(player, spec.draw_rooms) if spec.draw_rooms
    @ability_deck.discard(card)
    self
  end

  # The dungeon owner discards a room card to boost one of their boostable rooms.
  def boost_room(card_id, room_index)
    owner, = @current_crawl
    return self unless owner

    room = owner.dungeon.rooms.fetch(room_index)
    return self if @crawl_mods.boosted?(room_index)

    discard = owner.take_room_from_hand(card_id)
    raise ArgumentError, "room not in hand: #{card_id.inspect}" unless discard

    @room_deck.discard(discard)
    spec = RoomEffect.for(room).discard_boost
    if spec
      # add_damage stacks on the room's own (possibly upgraded) damage; set_damage
      # overrides it. unreducible makes the room's damage unreducible this crawl.
      @crawl_mods.add_damage(room_index, spec["add_damage"]) if spec["add_damage"]
      @crawl_mods.set_damage!(room_index, spec["set_damage"]) if spec["set_damage"]
      @crawl_mods.unreducible!(room_index) if spec["unreducible"]
    end
    self
  end

  # The game is decided; no more turns.
  def over?
    @stage == :over
  end

  attr_reader :winner

  # Final standings (best first) for display.
  def standings
    Scoreboard.standings(@players)
  end

  # True while parties are still waiting to be sent into a dungeon this turn.
  def crawling?
    @stage == :crawling
  end

  # True on a round where no hero attacked: players may play no-attack abilities
  # (Blueprints) before continuing.
  def quiet?
    @stage == :quiet
  end

  # End a quiet round once the player is done playing no-attack abilities:
  # everyone draws an ability card, then Recruitment runs and the turn is over.
  def finish_quiet_round
    return self unless quiet?

    grant_ability_cards # quiet round reward: no hero attacked a dungeon
    finish_turn
    self
  end

  # The next [player, party] waiting to crawl, or nil.
  def next_crawl
    @current_crawl
  end

  # --- decision loop ---

  def current_decision
    @decisions.first
  end

  def awaiting_decision?
    !@decisions.empty?
  end

  # True when nothing is pending (no decisions, no crawls) and a round may start.
  def ready?
    @decisions.empty? && @current_crawl.nil? && @stage == :ready
  end

  # Apply the player's choice to the current decision. `choice_id` is a card id,
  # or nil to skip (only allowed for skippable decisions). `target` is used only
  # by build decisions ("new" or a room index to replace). After applying, any
  # following decisions belonging to agent-controlled players resolve on their
  # own.
  def decide(choice_id, target = nil)
    raise "no pending decision" if @decisions.empty?

    process_head(choice_id, target)
    auto_advance
    self
  end

  # Whether the given player is controlled by an automated agent.
  def automated?(player)
    @agents.key?(player)
  end

  # A player with 5+ wounds is eliminated: out of the game (no turns, no heroes
  # lured to their dungeon, no arrivals generated for them).
  def eliminated?(player)
    Scoreboard.eliminated?(player)
  end

  # Players still in the game.
  def living_players
    @players.reject { |p| Scoreboard.eliminated?(p) }
  end

  # True while the human's most recent mandatory discard can still be taken back
  # (i.e. they have discarded but not yet finished building this turn).
  def can_undo_discard?
    return false unless @undoable_discard

    decision = current_decision
    player, = @undoable_discard
    decision && decision.kind == :build_room && decision.player == player
  end

  # Return the most recently discarded room card to its owner's hand and
  # re-prompt the discard. No-op unless an undo is currently available.
  def undo_discard
    return self unless can_undo_discard?

    player, card = @undoable_discard
    reclaimed = @room_deck.reclaim(card)
    player.add_room_to_hand(reclaimed) if reclaimed
    # dup the hand: Decision freezes its options, and freezing the player's live
    # hand array would break the next discard.
    @decisions.unshift(Decision.new(kind: :discard_room, player: player,
                                    options: player.room_hand.dup, allow_skip: false))
    @undoable_discard = nil
    self
  end

  # --- boss candidate storage (used by SetupPhase) ---

  def set_boss_candidates(player, bosses)
    @boss_candidates[player] = bosses
  end

  def boss_candidates(player)
    @boss_candidates.fetch(player, [])
  end

  def clear_boss_candidates(player)
    @boss_candidates.delete(player)
  end

  # --- town management (used by the phases) ---

  # Heroes arrive as lone parties (a party of one).
  def add_to_town(hero)
    @town << Party.new([hero])
  end

  # Remove a party from town (used when all its members die, or when it merges).
  def remove_party_from_town(party)
    @town.delete(party)
  end

  private

  # Pull parties off the queue (town order) until one enters a dungeon at the
  # current points, pausing there for the pre-crawl window. Parties that won't
  # enter (unenticed or too timid now) wait for Recruitment. Bait is therefore
  # re-evaluated for each party as points change. When the queue is exhausted,
  # the turn ends.
  def advance_to_next_crawl
    until @crawl_queue.empty?
      party = @crawl_queue.shift
      player = BaitPhase.target_for(self, party)
      if player
        @current_crawl = [player, party]
        @crawl_mods = CrawlModifiers.new
        @any_entered = true
        return
      end

      @waiting_parties << party
    end

    @current_crawl = nil
    if @any_entered
      finish_turn
    else
      # Quiet round: no hero attacked. Pause so players may play no-attack
      # abilities (Blueprints) before the turn ends.
      @stage = :quiet
    end
  end

  # Run Recruitment on the parties that stayed in town and end the turn.
  def finish_turn
    RecruitmentPhase.run(self, @waiting_parties)
    @waiting_parties = []
    @stage = :ready
  end

  def build_agents(agents_by_name)
    @players.each_with_object({}) do |player, map|
      agent = agents_by_name[player.name]
      next unless agent

      # Some agents (e.g. LogicAgent) read the town to weigh their choices.
      agent.attach(self) if agent.respond_to?(:attach)
      map[player] = agent
    end
  end

  # Resolve, in turn, every leading decision that belongs to an automated
  # player, using that player's agent to choose.
  def auto_advance
    while (decision = @decisions.first) && (agent = @agents[decision.player])
      choice_id, target = agent.choose(decision)
      process_head(choice_id, target)
    end
  end

  # Apply a choice to the head decision (shared by human and agent paths).
  def process_head(choice_id, target = nil)
    decision = @decisions.shift
    if choice_id.nil? && !decision.allow_skip
      raise ArgumentError, "#{decision.kind} requires a choice"
    end

    # A discard becomes the new undoable action; any other choice closes the
    # window on the previous discard.
    @undoable_discard = nil unless decision.kind == :discard_room
    apply(decision, choice_id, target)
    resolve_if_idle
  end

  def enqueue(kind, player, options, allow_skip: false, mandatory: false)
    @decisions << Decision.new(kind: kind, player: player,
                               options: options.dup, allow_skip: allow_skip && !mandatory)
  end

  def apply(decision, choice_id, target)
    case decision.kind
    when :choose_boss      then SetupPhase.choose_boss(self, decision.player, choice_id)
    when :place_first_room then SetupPhase.place_first_room(self, decision.player, choice_id)
    when :discard_room     then @undoable_discard = [decision.player, BuildPhase.discard(self, decision.player, choice_id)]
    when :build_room       then BuildPhase.place(self, decision.player, choice_id, target)
    end
  end

  # When the decision queue empties, finish whatever stage we were collecting
  # choices for.
  def resolve_if_idle
    return unless @decisions.empty?

    case @stage
    when :setup
      @stage = :ready
    when :building
      # Bait + Crawl are combined: parties are evaluated for entry one at a time
      # (in town order) as points change, rather than all up front.
      @crawl_queue = @town.dup
      @waiting_parties = []
      @any_entered = false
      @stage = :crawling
      advance_to_next_crawl
    end
  end

  # An automated owner uses discard-to-boost on its own boostable rooms before a
  # crawl (it does not play ability cards yet).
  def agent_pre_crawl(owner)
    return unless automated?(owner)

    owner.dungeon.rooms.each_index do |i|
      next unless RoomEffect.for(owner.dungeon.rooms[i]).boostable?
      next if @crawl_mods.boosted?(i)

      spare = owner.room_hand.first
      boost_room(spare.id, i) if spare && @rng.rand < 0.5
    end
  end

  # After a crawl, a player draws cards triggered by death-on-death rooms
  # (Soul Siphon draws ability cards; Unhallowed Ground draws room cards).
  def apply_death_draws(player, result)
    return unless result

    (result.draws["ability"] || 0).times { player.add_ability_to_hand(@ability_deck.draw) }
    draw_rooms_for(player, result.draws["room"] || 0)
  end

  # Draw n cards from the build deck into a player's hand (respecting the cap).
  def draw_rooms_for(player, count)
    count.times do
      card = @room_deck.draw
      @room_deck.discard(card) unless player.add_room_to_hand(card)
    end
  end

  # Each player draws an ability card on a round where no hero attacked.
  def grant_ability_cards
    @players.each { |player| player.add_ability_to_hand(@ability_deck.draw) }
  end
end
