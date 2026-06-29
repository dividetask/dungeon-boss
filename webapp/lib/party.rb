# frozen_string_literal: true

# A group of one or more heroes that bait, crawl, and score together. A lone
# hero is simply a party of one. Heroes are held in board order (the order they
# were added), which is the final tie-breaker when a room chooses a target.
class Party
  attr_reader :heroes
  attr_accessor :name

  def initialize(heroes, name: nil)
    @heroes = heroes
    @name = name
  end

  def named?
    !@name.nil?
  end

  def size
    @heroes.size
  end

  def lone?
    @heroes.size == 1
  end

  def empty?
    @heroes.empty?
  end

  # Combined courage of all members (used for the dungeon courage check).
  def courage
    @heroes.sum(&:courage)
  end

  def add(hero)
    @heroes << hero
    self
  end

  # Remove a single hero (e.g. when it dies).
  def remove(hero)
    index = @heroes.index(hero)
    @heroes.delete_at(index) if index
    self
  end

  # The party's generated name if it has one, otherwise the members joined,
  # e.g. "Cleric" (lone) or "The Doomed Fellowship".
  def display_name
    @name || @heroes.map(&:name).join(" & ")
  end

  # The members listed, e.g. "Cleric & Barbarian".
  def roster
    @heroes.map(&:name).join(" & ")
  end
end
