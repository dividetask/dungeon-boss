# frozen_string_literal: true

require "yaml"
require_relative "boss"
require_relative "room"
require_relative "upgrade"
require_relative "hero"
require_relative "ability_card"

# Loads the card definitions and exposes the full pools of cards as model
# objects. Each definition may set `copies` (default 1) to put several identical
# cards in the deck; the pools returned here are those physical cards, expanded.
# Validates the data while building. Holds no game-flow logic.
class CardLibrary
  attr_reader :bosses, :rooms, :upgrades, :advanced_rooms, :heroes, :ability_cards

  # @param path [String] a directory of `*.yaml` card files (bosses, rooms,
  #   heroes, abilities — their top-level keys are merged), or a single YAML file.
  def self.load(path)
    data =
      if File.directory?(path)
        Dir.glob(File.join(path, "*.yaml")).sort.each_with_object({}) do |file, merged|
          (YAML.safe_load_file(file) || {}).each { |key, list| (merged[key] ||= []).concat(Array(list)) }
        end
      else
        YAML.safe_load_file(path) || {}
      end
    new(data)
  end

  def initialize(data)
    @bosses = expand(data["bosses"]) { |c| build_boss(c) }
    @rooms = expand(data["rooms"]) { |c| build_room(c) }
    @upgrades = expand(data["upgrades"]) { |c| build_upgrade(c) }
    @advanced_rooms = expand(data["advanced_rooms"]) { |c| build_room(c.merge("advanced" => true)) }
    @heroes = expand(data["heroes"]) { |c| build_hero(c) }
    @ability_cards = expand(data["ability_cards"]) { |c| build_ability(c) }
  end

  private

  # Turn a list of definitions into the physical card pile: each definition
  # becomes `copies` *distinct* card objects (built fresh, so duplicate cards are
  # never the same instance). Definition ids must be unique.
  def expand(list)
    definitions = list || []
    assert_unique_ids(definitions)
    definitions.flat_map do |entry|
      Array.new(Integer(entry["copies"] || 1)) { yield(entry) }
    end
  end

  def build_boss(c)
    Boss.new(id: c.fetch("id"), name: c.fetch("name"),
             damage: c.fetch("damage"), bait: c["bait"],
             effect: c["effect"], tags: c["tags"],
             ability_text: c["ability_text"].to_s)
  end

  def build_room(c)
    Room.new(id: c.fetch("id"), name: c.fetch("name"),
             type: c.fetch("type"), damage: c.fetch("damage"),
             bait: c["bait"], description: c["description"].to_s,
             effect: c["effect"], tags: c["tags"], advanced: c["advanced"])
  end

  def build_upgrade(c)
    Upgrade.new(id: c.fetch("id"), name: c.fetch("name"),
                bonus_damage: c.fetch("bonus_damage", 0), bait: c["bait"],
                description: c["description"].to_s, tags: c["tags"])
  end

  def build_hero(c)
    Hero.new(id: c.fetch("id"), name: c.fetch("name"),
             health: c.fetch("health"),
             preferred_bait: c.fetch("preferred_bait"),
             courage: c.fetch("courage", 1), tags: c["tags"],
             ability_text: c["ability_text"].to_s)
  end

  def build_ability(c)
    AbilityCard.new(id: c.fetch("id"), name: c.fetch("name"),
                    text: c["text"].to_s, effect: c["effect"], tags: c["tags"])
  end

  def assert_unique_ids(definitions)
    ids = definitions.map { |d| d["id"] }
    dupes = ids.select { |id| ids.count(id) > 1 }.uniq
    raise ArgumentError, "duplicate card ids: #{dupes.join(', ')}" if dupes.any?
  end
end
