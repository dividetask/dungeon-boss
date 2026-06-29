# frozen_string_literal: true

require_relative "../upgrade"

# Build phase. For each player:
#   - draw two room cards into hand
#   - the player CHOOSES one room from hand to discard
#   - the player CHOOSES one room from hand to place, or to build nothing
#
# A room may be placed as a new entrance (to the left) when the dungeon is not
# full, or placed on top of an existing room to replace it (the replaced room is
# discarded). A dungeon holds at most Dungeon::MAX_ROOMS rooms.
#
# This phase exposes the steps the game's decision loop drives; it does not pick
# for the player. Orchestration only.
class BuildPhase
  DRAW_COUNT = 2

  # Draw two rooms into each player's hand, up to the hand cap; any room that
  # does not fit is discarded back to the deck.
  def self.draw_for_each(game)
    game.living_players.each do |player|
      game.room_deck.draw_many(DRAW_COUNT).each do |room|
        game.room_deck.discard(room) unless player.add_room_to_hand(room)
      end
    end
  end

  # Discard a chosen room from the player's hand (mandatory each Build phase).
  # Returns the discarded card so the caller can offer an undo.
  def self.discard(game, player, room_id)
    room = player.take_room_from_hand(room_id)
    raise ArgumentError, "room not in hand: #{room_id.inspect}" unless room

    game.room_deck.discard(room)
    room
  end

  # Apply a player's build choice.
  #   card_id: the card from hand to play, or nil to build nothing
  #   target:  basic room -> "new" (or nil) to add an entrance, or a room index
  #            to replace; upgrade or advanced room -> the room index it targets.
  def self.place(game, player, card_id, target = nil)
    return if card_id.nil?

    card = player.take_room_from_hand(card_id)
    raise ArgumentError, "card not in hand: #{card_id.inspect}" unless card

    if card.is_a?(Upgrade)
      replaced = player.dungeon.apply_upgrade(Integer(target), card)
      game.room_deck.discard(replaced) if replaced # an upgrade it displaced
    elsif card.advanced?
      place_advanced(game, player, card, Integer(target))
    elsif target.nil? || target.to_s == "new"
      player.dungeon.add_room_to_left(card)
    else
      discard_replaced(game, player.dungeon.replace_room(Integer(target), card))
    end
  end

  # An advanced room may replace any room that shares at least one of its bait
  # icons.
  def self.place_advanced(game, player, card, index)
    placed = player.dungeon.rooms.fetch(index)
    unless placed.bait.shares?(card.bait)
      raise ArgumentError, "advanced room needs a room sharing at least one bait icon"
    end

    discard_replaced(game, player.dungeon.replace_room(index, card))
  end

  # Return a replaced PlacedRoom's base room (and any upgrade) to the deck.
  def self.discard_replaced(game, old)
    game.room_deck.discard(old.base_room)
    game.room_deck.discard(old.upgrade) if old.upgrade
  end
end
