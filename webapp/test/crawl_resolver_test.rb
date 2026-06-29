# frozen_string_literal: true

require_relative "test_helper"

class CrawlResolverTest < Minitest::Test
  def hero(health)
    Hero.new(id: "h", name: "Hero", health: health, preferred_bait: :glory)
  end

  def room(damage)
    Room.new(id: "r", name: "Room", type: "trap", damage: damage, bait: {})
  end

  def boss(damage)
    Boss.new(id: "b", name: "Boss", damage: damage, bait: {})
  end

  def test_hero_dies_when_damage_exceeds_health
    dungeon = Dungeon.new(boss(2))
    dungeon.add_room_to_left(room(3)) # entrance
    dungeon.add_room_to_left(room(2)) # new entrance, resolved first

    result = CrawlResolver.resolve(hero(4), dungeon)

    assert_equal :died, result.outcome
    assert_operator result.remaining_health, :<=, 0
  end

  def test_hero_escapes_when_it_survives_the_boss
    dungeon = Dungeon.new(boss(1))
    dungeon.add_room_to_left(room(1))

    result = CrawlResolver.resolve(hero(5), dungeon)

    assert_equal :escaped, result.outcome
    assert_equal 3, result.remaining_health
  end

  def test_resolution_stops_at_death
    dungeon = Dungeon.new(boss(10))
    dungeon.add_room_to_left(room(10)) # entrance kills first

    result = CrawlResolver.resolve(hero(5), dungeon)

    assert_equal :died, result.outcome
    assert_equal 1, result.log.size # boss never reached
  end
end
