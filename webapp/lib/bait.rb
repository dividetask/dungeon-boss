# frozen_string_literal: true

# The set of valid bait types and validation of a bait name.
# Bait types are exactly: glory, riches, undead, power.
module Bait
  GLORY  = :glory
  RICHES = :riches
  UNDEAD = :undead
  POWER  = :power

  ALL = [GLORY, RICHES, UNDEAD, POWER].freeze

  module_function

  # Normalize an arbitrary value (string or symbol) to a known bait symbol,
  # raising if it is not one of the four valid types.
  def normalize(value)
    bait = value.to_s.downcase.to_sym
    return bait if ALL.include?(bait)

    raise ArgumentError, "unknown bait type: #{value.inspect}"
  end

  def valid?(value)
    ALL.include?(value.to_s.downcase.to_sym)
  end
end
