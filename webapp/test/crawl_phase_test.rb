# frozen_string_literal: true

require_relative "test_helper"

class CrawlPhaseTest < Minitest::Test
  # Minimal game double exposing what CrawlPhase.resolve_party needs.
  class GameDouble
    attr_reader :town

    def initialize(town)
      @town = town
    end

    def remove_party_from_town(party)
      @town.delete(party)
    end
  end

  def hero(id, health)
    Hero.new(id: id, name: id, health: health, preferred_bait: :glory)
  end

  def dungeon(boss_damage, room_damages = [])
    d = Dungeon.new(Boss.new(id: "b", name: "Boss", damage: boss_damage, bait: {}))
    room_damages.each { |dmg| d.add_room_to_left(Room.new(id: "r#{dmg}", name: "R", type: "trap", damage: dmg, bait: {})) }
    d
  end

  def player(dungeon)
    p = Player.new("P")
    p.dungeon = dungeon
    p
  end

  def test_rooms_target_the_highest_health_hero
    a = hero("a", 5)
    b = hero("b", 10)
    party = Party.new([a, b])
    pl = player(dungeon(3)) # single encounter: the boss
    game = GameDouble.new([party])

    CrawlPhase.resolve_party(game, pl, party)

    # The boss hit the higher-health hero (b), not a.
    assert_equal 0, pl.points
    assert_equal 1, pl.wounds # someone survived
    assert_equal [party], game.town
  end

  def test_each_death_scores_and_wipeout_gives_no_wound
    party = Party.new([hero("a", 1), hero("b", 1)])
    pl = player(dungeon(100, [100])) # room then boss, each lethal
    game = GameDouble.new([party])

    CrawlPhase.resolve_party(game, pl, party)

    assert_equal 2, pl.points  # both died -> two points
    assert_equal 0, pl.wounds  # nobody survived -> no wound
    assert_empty game.town     # party wiped out, removed from town
  end

  def test_retreat_at_the_entrance_is_a_full_retreat
    party = Party.new([hero("a", 5)])
    pl = player(dungeon(100)) # would normally kill
    game = GameDouble.new([party])
    mods = CrawlModifiers.new.tap { |m| m.retreat!(0) } # turn back before any room

    outcome = CrawlPhase.resolve_party(game, pl, party, mods)

    assert outcome.retreated
    assert_empty outcome.result.log # no encounters resolved
    assert_equal 0, pl.points
    assert_equal 0, pl.wounds
    assert_equal [party], game.town # still in town, unharmed
  end

  def test_partial_retreat_scores_early_deaths_but_saves_the_rest
    a = hero("a", 2)
    b = hero("b", 10)
    party = Party.new([a, b])
    # entrance kills b (highest health), then a lethal room, then the boss.
    pl = player(dungeon(100, [100, 10])) # rooms added left: index0 dmg10, index1 dmg100
    game = GameDouble.new([party])
    mods = CrawlModifiers.new.tap { |m| m.retreat!(1) } # crawl room 0, then turn back

    outcome = CrawlPhase.resolve_party(game, pl, party, mods)

    assert outcome.retreated
    assert_equal 1, pl.points # b died in room 0 -> a point
    assert_equal 0, pl.wounds # the party retreated -> no wound despite a surviving
    assert_includes outcome.result.survivors, a # a was saved from the lethal room/boss
    assert_equal [party], game.town # a is still in town
  end

  def test_outcome_records_the_pre_crawl_points_that_applied
    party = Party.new([hero("a", 1), hero("b", 1)]) # both die, scoring 2 points
    pl = player(dungeon(100, [100]))
    pl.points = 3 # owner already had 3 points going in
    game = GameDouble.new([party])

    outcome = CrawlPhase.resolve_party(game, pl, party)

    assert_equal 3, outcome.boss_bonus # the bonus that applied to THIS crawl
    assert_equal 5, pl.points          # 3 + 2 scored — only counts for the next crawl
  end

  def test_partial_death_scores_a_point_and_one_wound
    squishy = hero("squishy", 1)
    tank = hero("tank", 100)
    party = Party.new([squishy, tank])
    pl = player(dungeon(100, [1])) # room dmg 1, boss dmg 100; both hit the tank
    game = GameDouble.new([party])

    CrawlPhase.resolve_party(game, pl, party)

    assert_equal 1, pl.points   # the tank died
    assert_equal 1, pl.wounds   # the squishy survived -> one wound
    assert_equal [squishy], party.heroes # dead removed, survivor stays
    assert_equal [party], game.town
  end
end
