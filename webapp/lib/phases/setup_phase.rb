# frozen_string_literal: true

require_relative "../dungeon"
require_relative "../room"

# Setup phase (runs once at game start). The player makes the choices:
#   - they are dealt a starting hand (mulliganed until it holds a room)
#   - they are dealt two boss candidates and CHOOSE one (the other is discarded)
#   - they CHOOSE one room from hand to place beside their boss
#
# This phase exposes the discrete steps the game's decision loop drives; it does
# not pick for the player. Orchestration only.
class SetupPhase
  STARTING_ROOMS = 4
  STARTING_ABILITIES = 2
  BOSS_CANDIDATES = 2

  # Deal opening hands and draw each player's boss candidates. After this, every
  # player is awaiting a boss choice and a first-room choice.
  def self.deal(game)
    game.players.each do |player|
      deal_starting_hand(game, player)
      game.ability_deck.draw_many(STARTING_ABILITIES).each { |a| player.add_ability_to_hand(a) }
      game.set_boss_candidates(player, game.boss_deck.draw_many(BOSS_CANDIDATES))
    end
  end

  # Deal a starting hand, mulliganing (redrawing) until it contains at least one
  # basic room — only a basic room can be placed as the first room.
  def self.deal_starting_hand(game, player)
    loop do
      drawn = game.room_deck.draw_many(STARTING_ROOMS)
      if drawn.any? { |card| card.is_a?(Room) && !card.advanced? }
        drawn.each { |card| player.add_room_to_hand(card) }
        return
      end

      drawn.each { |card| game.room_deck.discard(card) } # mulligan
    end
  end

  # Apply a player's boss choice: keep the chosen card, discard the rest.
  def self.choose_boss(game, player, boss_id)
    candidates = game.boss_candidates(player)
    chosen = candidates.find { |boss| boss.id == boss_id }
    raise ArgumentError, "boss not among candidates: #{boss_id.inspect}" unless chosen

    (candidates - [chosen]).each { |boss| game.boss_deck.discard(boss) }
    player.dungeon = Dungeon.new(chosen)
    game.clear_boss_candidates(player)
  end

  # Apply a player's first-room choice (mandatory; must be an actual room).
  def self.place_first_room(_game, player, room_id)
    room = player.take_room_from_hand(room_id)
    raise ArgumentError, "room not in hand: #{room_id.inspect}" unless room
    raise ArgumentError, "first placement must be a room" unless room.is_a?(Room)

    player.dungeon.add_room_to_left(room)
  end
end
