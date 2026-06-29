# frozen_string_literal: true

require_relative "placed_room"

# One player's dungeon: a boss plus an ordered list of rooms.
# The entrance is the leftmost room; the boss sits on the right.
# Rooms are added to the left, so the dungeon grows toward the entrance.
# A dungeon holds at most MAX_ROOMS rooms (the boss does not count).
class Dungeon
  MAX_ROOMS = 5

  attr_reader :boss, :rooms

  def initialize(boss)
    @boss = boss
    @rooms = [] # index 0 is the leftmost (entrance) room
  end

  # True when the dungeon already holds the maximum number of rooms.
  def full?
    @rooms.size >= MAX_ROOMS
  end

  # Place a room to the left of the current leftmost room (new entrance).
  def add_room_to_left(room)
    raise "dungeon is full (max #{MAX_ROOMS} rooms)" if full?

    @rooms.unshift(PlacedRoom.new(room))
    self
  end

  # Replace the room at the given index with a new one, returning the old
  # PlacedRoom (so the caller can discard it; any upgrade on it is lost).
  def replace_room(index, room)
    old = @rooms.fetch(index)
    @rooms[index] = PlacedRoom.new(room)
    old
  end

  # Attach an upgrade to the room at the given index, returning the upgrade it
  # replaced (or nil). Each room holds at most one upgrade.
  def apply_upgrade(index, upgrade)
    placed = @rooms.fetch(index)
    previous = placed.upgrade
    placed.upgrade = upgrade
    previous
  end

  # The leftmost (entrance) room, or nil if none yet.
  def entrance
    @rooms.first
  end

  # Encounters in crawl order: rooms left -> right, then the boss last.
  # Each encounter responds to #damage, so the resolver can treat them
  # uniformly.
  def encounters
    @rooms + [@boss]
  end
end
