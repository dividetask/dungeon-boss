# frozen_string_literal: true

# A participant in the game. Holds the player's dungeon, hand of room cards
# (and ability cards, unused in v1), score, and the heroes assigned to their
# dungeon this round. Holds no phase logic.
class Player
  MAX_ROOM_HAND = 6

  attr_reader :name, :room_hand, :ability_hand
  attr_accessor :dungeon, :points, :wounds

  def initialize(name)
    @name = name
    @dungeon = nil
    @room_hand = []
    @ability_hand = []
    @points = 0
    @wounds = 0
  end

  # Add a room to hand unless the hand is at its cap. Returns true if added.
  def add_room_to_hand(room)
    return false if room.nil? || room_hand_full?

    @room_hand << room
    true
  end

  def room_hand_full?
    @room_hand.size >= MAX_ROOM_HAND
  end

  def add_ability_to_hand(card)
    @ability_hand << card if card
  end

  # Remove and return an ability card from hand by id, or nil if not held.
  def take_ability_from_hand(card_id)
    index = @ability_hand.index { |card| card.id == card_id }
    index ? @ability_hand.delete_at(index) : nil
  end

  # Remove and return a room from hand by id, or nil if not held.
  def take_room_from_hand(room_id)
    index = @room_hand.index { |room| room.id == room_id }
    index ? @room_hand.delete_at(index) : nil
  end

  def gain_point
    @points += 1
  end

  def gain_wound
    @wounds += 1
  end
end
