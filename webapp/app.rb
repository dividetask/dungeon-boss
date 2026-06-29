# frozen_string_literal: true

require "sinatra/base"
require "json"
require_relative "lib/card_library"
require_relative "lib/game"
require_relative "lib/crawl_log"
require_relative "lib/random_agent"
require_relative "lib/card_presenter"
require_relative "lib/dungeon_summary"
require_relative "lib/boss_effect"
require_relative "lib/room_effect"
require_relative "lib/ability_effect"

# Thin web layer for testing the game engine. It translates HTTP actions into
# Game calls and renders the resulting state. All game rules live in lib/.
#
# The web app is a hotseat rules-testing harness: it prompts the players, in
# turn, for the choices the rules call for (which boss to keep, which room to
# place). One in-memory game is kept per server process.
class DungeonBossApp < Sinatra::Base
  CARDS_PATH = File.expand_path("../data/cards", __dir__)
  LOG_PATH = File.expand_path("log/crawl.log", __dir__)
  HUMAN_PLAYER = "Player 1"
  MIN_PLAYERS = 2
  MAX_PLAYERS = 4
  DEFAULT_PLAYERS = 2

  set :views, File.expand_path("views", __dir__)

  configure do
    set :game, nil
  end

  helpers do
    # The human player (the one not controlled by an agent).
    def human_player
      game = settings.game
      game && game.players.find { |p| !game.automated?(p) }
    end

    # Total bonus damage a room gets during a crawl beyond its own printed
    # damage: the boss's room aura (Goblin Chieftain / Kobold / Necromancer) plus
    # dungeon room auras (Beast Tamer / Trap Makers). 0 when none applies.
    def room_bonus_total(player, room)
      dungeon = player.dungeon
      points = applicable_points(player)
      boss = BossEffect.for(dungeon.boss).room_bonus(room, points)
      auras = dungeon.rooms.sum { |r| r.equal?(room) ? 0 : RoomEffect.for(r).aura_bonus(room, points) }
      boss + auras
    end

    # The boss's own bonus damage from its owner's points (×N for bosses like
    # the Malevolent Spirit; ×1 for ordinary bosses).
    def boss_self_bonus(player)
      BossEffect.for(player.dungeon.boss).self_bonus(applicable_points(player))
    end

    # The dungeon's effective total damage for the quick sheet: every room and
    # the boss at their current crawl damage (printed damage + points/aura bonus).
    def dungeon_total_damage(player)
      dungeon = player.dungeon
      rooms = dungeon.rooms.sum { |r| r.damage + room_bonus_total(player, r) }
      rooms + dungeon.boss.damage + boss_self_bonus(player)
    end

    # The point total used for a dungeon's damage display: the per-point bonus
    # that applies to the crawl in context. Points earned on PREVIOUS crawls
    # apply to the current one; only points scored DURING a crawl don't count
    # toward it (the snapshot handles that). So:
    #   - a crawl is queued against this dungeon  -> its current points (what
    #     will apply when it's sent in),
    #   - this dungeon's crawl just resolved       -> the snapshot that applied,
    #   - otherwise (building, or idle while someone else crawls) -> current.
    def applicable_points(player)
      game = settings.game

      pending = game.next_crawl
      return player.points if pending && pending.first == player

      finished = game.last_outcomes.find { |o| o.player == player }
      finished ? finished.boss_bonus : player.points
    end

    # The damage breakdown shown on an encounter card: base first, then each
    # positive bonus (upgrade, grow, auras, points). Points-based bonuses are
    # excluded while a crawl is in progress — they apply to the next crawl, not
    # the one being animated — and reflect the just-finished crawl once it ends.
    def damage_parts(player, encounter)
      points = applicable_points(player)
      dungeon = player.dungeon
      if encounter.equal?(dungeon.boss)
        parts = [encounter.damage]
        self_bonus = BossEffect.for(dungeon.boss).self_bonus(points)
        parts << self_bonus if self_bonus.positive?
      else
        parts = [encounter.base_room.damage]
        upgrade = encounter.upgrade
        parts << upgrade.bonus_damage if upgrade && upgrade.bonus_damage.positive?
        parts << encounter.grow if encounter.grow.positive?
        aura = BossEffect.for(dungeon.boss).room_bonus(encounter, points) +
               dungeon.rooms.sum { |r| r.equal?(encounter) ? 0 : RoomEffect.for(r).aura_bonus(encounter, points) }
        parts << aura if aura.positive?
      end
      parts
    end
  end

  get "/" do
    erb :index, locals: { game: settings.game }
  end

  post "/new" do
    library = CardLibrary.load(CARDS_PATH)
    # Player 1 is you; Players 2..N (up to MAX_PLAYERS) are random computer
    # opponents. The hero count per turn equals the number of players.
    count = (params["players"] || DEFAULT_PLAYERS).to_i.clamp(MIN_PLAYERS, MAX_PLAYERS)
    names = (1..count).map { |i| "Player #{i}" }
    agents = names.drop(1).to_h { |name| [name, RandomAgent.new] }
    settings.game = Game.new(library, names, agents: agents, log: CrawlLog.new(LOG_PATH)).start
    redirect "/"
  end

  # Resolve the current pending decision. A blank/absent choice means "skip"
  # (only valid for skippable decisions, e.g. building nothing). `target` is used
  # by build decisions: "new" to add a room, or a room index to replace one.
  post "/decide" do
    choice = params["choice"]
    choice = nil if choice.nil? || choice.empty?
    target = params["target"]
    target = nil if target.nil? || target.empty?
    settings.game&.decide(choice, target)
    redirect "/"
  end

  post "/round" do
    settings.game&.start_round if settings.game&.ready?
    redirect "/"
  end

  # Take back the human's most recent build-phase discard and re-prompt it.
  post "/undo_discard" do
    settings.game&.undo_discard
    redirect "/"
  end

  # Finish a quiet round (no hero attacked) after any no-attack abilities.
  post "/continue" do
    settings.game&.finish_quiet_round
    redirect "/"
  end

  # Play an ability card (the human) on the current crawl. `target` is a room
  # index, or blank for party-targeting (Retreat).
  post "/play_ability" do
    target = params["target"]
    target = nil if target.nil? || target.empty?
    settings.game&.play_ability(human_player, params["choice"], target)
    redirect "/"
  end

  # The dungeon owner discards a room card to boost one of their rooms.
  post "/boost" do
    settings.game&.boost_room(params["choice"], Integer(params["target"]))
    redirect "/"
  end

  # Send the next party into its dungeon (one per click).
  post "/crawl" do
    settings.game&.send_next_party
    redirect "/"
  end

  run! if app_file == $PROGRAM_NAME
end
