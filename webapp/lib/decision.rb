# frozen_string_literal: true

# A choice the game is waiting for a player to make. Holds no logic — it only
# describes what is being decided, by whom, and the available options.
#
# kind:
#   :choose_boss       pick one of two drawn boss cards (the other is discarded)
#   :place_first_room  pick a room from hand to place beside the boss (Setup)
#   :discard_room      pick a room from hand to throw away (Build)
#   :build_room        pick a room from hand to place, or skip (Build)
class Decision
  KINDS = %i[choose_boss place_first_room discard_room build_room].freeze

  attr_reader :kind, :player, :options, :allow_skip

  # @param options [Array] the cards the player may choose among
  # @param allow_skip [Boolean] whether choosing nothing is permitted
  def initialize(kind:, player:, options:, allow_skip: false)
    raise ArgumentError, "unknown decision kind: #{kind}" unless KINDS.include?(kind)

    @kind = kind
    @player = player
    @options = (options || []).freeze
    @allow_skip = allow_skip
    freeze
  end

  def prompt
    case kind
    when :choose_boss
      "#{player.name}: choose your boss (the other is discarded)"
    when :place_first_room
      "#{player.name}: choose a room to place beside your boss"
    when :discard_room
      "#{player.name}: discard a room card"
    when :build_room
      "#{player.name}: choose a room to add to your dungeon, or build nothing"
    end
  end
end
