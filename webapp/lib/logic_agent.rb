# frozen_string_literal: true

require "yaml"

require_relative "bait"
require_relative "bait_icons"
require_relative "dungeon"
require_relative "upgrade"
require_relative "dungeon_forecast"

# An automated player driven by declarative heuristics (data/ai_logic.yaml).
# Like RandomAgent it answers a Decision with a [choice_id, target] pair, but
# instead of picking at random it scores the candidates with the tie-break chain
# configured for that decision (see the file and docs/ai.md for the vocabulary).
#
# The agent is the interpreter; the YAML is the logic. To weigh a placement by
# how it would actually play out, the agent needs to know who is in town, so the
# Game attaches itself via #attach when it builds its agents. Without a game (or
# when the town is empty) the simulation comparators score 0 and the chain falls
# through to the static ones.
class LogicAgent
  # @param logic [Hash] parsed ai_logic.yaml (decision kind => array of rules)
  # @param rng [Random] breaks ties between equally-ranked candidates
  def initialize(logic = {}, rng: Random.new)
    @logic = logic || {}
    @rng = rng
    @game = nil
  end

  # Load the heuristics from a YAML file.
  def self.load(path, rng: Random.new)
    new(YAML.safe_load_file(path) || {}, rng: rng)
  end

  # The Game calls this so the agent can read the town for simulations.
  def attach(game)
    @game = game
    self
  end

  # @param decision [Decision]
  # @return [Array(String, Object), Array(nil, nil)]
  def choose(decision)
    candidates = candidates_for(decision)
    return [nil, nil] if candidates.empty?

    chosen = select(candidates, @logic[decision.kind.to_s] || [], decision)
    [chosen.id, chosen.target]
  end

  private

  # A possible answer to a decision: the card to play (or nil to skip), the build
  # target, and — for build moves — the dungeon the move would produce (so the
  # simulation comparators can crawl it). `forecast` is memoised per candidate.
  Candidate = Struct.new(:id, :target, :card, :dungeon) do
    def forecast(parties, boss_bonus)
      @forecast ||= DungeonForecast.run(dungeon, parties, boss_bonus: boss_bonus)
    end
  end

  def candidates_for(decision)
    case decision.kind
    when :build_room then build_moves(decision)
    when :discard_room
      decision.player.room_hand.map { |card| Candidate.new(card.id, nil, card, nil) }
    else
      decision.options.map { |card| Candidate.new(card.id, nil, card, nil) }
    end
  end

  # Every legal build move (mirrors BuildPhase.place), each paired with the
  # dungeon it would produce, plus the option to build nothing.
  def build_moves(decision)
    dungeon = decision.player.dungeon
    moves = [Candidate.new(nil, nil, nil, dungeon)] # build nothing: dungeon unchanged

    decision.player.room_hand.each do |card|
      if card.is_a?(Upgrade)
        dungeon.rooms.each_index { |i| moves << move(card, i, dungeon) { |d| d.apply_upgrade(i, card) } }
      elsif card.advanced?
        dungeon.rooms.each_index do |i|
          next unless dungeon.rooms[i].bait.shares?(card.bait)

          moves << move(card, i, dungeon) { |d| d.replace_room(i, card) }
        end
      else
        moves << move(card, "new", dungeon) { |d| d.add_room_to_left(card) } unless dungeon.full?
        dungeon.rooms.each_index { |i| moves << move(card, i, dungeon) { |d| d.replace_room(i, card) } }
      end
    end
    moves
  end

  # Build a candidate from a move: clone the dungeon, apply the change to the
  # clone, and remember the card/target so we can report the choice.
  def move(card, target, dungeon)
    candidate_dungeon = DungeonForecast.clone(dungeon)
    yield candidate_dungeon
    Candidate.new(card.id, target, card, candidate_dungeon)
  end

  # Narrow the candidates with each rule in turn; break any final tie at random.
  def select(candidates, rules, decision)
    surviving = candidates
    rules.each do |rule|
      break if surviving.size <= 1

      surviving = keep_best(surviving, rule["prefer"], decision)
    end
    surviving.sample(random: @rng)
  end

  # Keep only the candidates whose score ties for the best under one comparator.
  def keep_best(candidates, comparator, decision)
    scorer = scorer_for(comparator)
    scored = candidates.map { |candidate| [candidate, scorer.call(candidate, decision)] }
    best = scored.map { |(_, score)| score }.max
    scored.select { |(_, score)| score == best }.map(&:first)
  end

  # A comparator name (or bait-priority list) -> a proc scoring a candidate so
  # that a HIGHER score is always better (minimising comparators are negated).
  def scorer_for(comparator)
    return bait_priority_scorer(comparator) if comparator.is_a?(Array)

    case comparator.to_s
    when "highest_damage"     then ->(c, _) { card_damage(c.card) }
    when "lowest_damage"      then ->(c, _) { -card_damage(c.card) }
    when "bait_count"         then ->(c, _) { card_bait(c.card).total }
    when "most_points"        then ->(c, d) { forecast(c, d).kills }
    when "fewest_wounds"      then ->(c, d) { -forecast(c, d).wounds }
    when "highest_avg_damage" then ->(c, d) { (forecast(c, d).avg_damage * 10_000).round }
    else raise ArgumentError, "unknown ai_logic comparator: #{comparator.inspect}"
    end
  end

  # Lexicographic score over the listed bait types: most of the first, then the
  # second, and so on. Arrays compare element-by-element, which is exactly this.
  def bait_priority_scorer(priority)
    order = priority.map { |bait| Bait.normalize(bait) }
    ->(candidate, _) { order.map { |bait| card_bait(candidate.card).count(bait) } }
  end

  # Run (and cache) the crawl forecast for a build candidate against the town.
  def forecast(candidate, decision)
    return DungeonForecast::Outcome.new(kills: 0, wounds: 0, total_damage: 0, hero_count: 0) if candidate.dungeon.nil?

    candidate.forecast(town, decision.player.points)
  end

  # The parties currently in town, or none if the agent has no game attached.
  def town
    @game ? @game.town : []
  end

  def card_damage(card)
    return 0 if card.nil?
    return card.bonus_damage if card.is_a?(Upgrade)

    card.respond_to?(:damage) ? card.damage : 0
  end

  def card_bait(card)
    card.respond_to?(:bait) ? card.bait : BaitIcons.new
  end
end
