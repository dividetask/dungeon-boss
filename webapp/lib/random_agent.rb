# frozen_string_literal: true

require_relative "dungeon"
require_relative "upgrade"

# An automated player. Given a Decision, it picks at random and returns a
# [choice_id, target] pair:
#   - choice_id: the chosen card id, or nil to skip
#   - target:    only meaningful for build decisions — "new" to add a room, or a
#                room index to replace an existing room
# Holds no game state.
class RandomAgent
  def initialize(rng = Random.new)
    @rng = rng
  end

  # @param decision [Decision]
  # @return [Array(String, Object), Array(nil, nil)]
  def choose(decision)
    case decision.kind
    when :build_room    then build_move(decision)
    when :discard_room  then [decision.player.room_hand.sample(random: @rng)&.id, nil]
    else [decision.options.sample(random: @rng)&.id, nil]
    end
  end

  private

  # Randomly build nothing, play a room (add/replace), apply an upgrade, or place
  # an advanced room on a matching room. Reads the live hand.
  def build_move(decision)
    pick = (decision.player.room_hand + [nil]).sample(random: @rng)
    return [nil, nil] if pick.nil?

    dungeon = decision.player.dungeon
    if pick.is_a?(Upgrade)
      return [nil, nil] if dungeon.rooms.empty?

      [pick.id, @rng.rand(dungeon.rooms.size)] # a room index to attach to
    elsif pick.advanced?
      indexes = (0...dungeon.rooms.size).select { |i| dungeon.rooms[i].bait.shares?(pick.bait) }
      return [nil, nil] if indexes.empty? # nowhere valid to place it

      [pick.id, indexes.sample(random: @rng)]
    else
      targets = []
      targets << "new" unless dungeon.full?
      dungeon.rooms.each_index { |i| targets << i }
      [pick.id, targets.sample(random: @rng)]
    end
  end
end
