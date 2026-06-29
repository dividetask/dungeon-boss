# frozen_string_literal: true

require_relative "test_helper"
require "tmpdir"

# CrawlLog appends a readable record of each crawl to a file (debugging aid).
class CrawlLogTest < Minitest::Test
  def setup
    @dir = Dir.mktmpdir
    @path = File.join(@dir, "nested", "crawl.log") # nested: dir is created
    @log = CrawlLog.new(@path)
  end

  def teardown
    FileUtils.remove_entry(@dir)
  end

  # Build a one-room dungeon and a frail hero so the crawl produces a death.
  def outcome_with_a_death
    room = Room.new(id: "succ", name: "Succubus", type: "Creature", damage: 2,
                    bait: { power: 1 }, effect: { "grows_on_death" => true }, advanced: true)
    boss = Boss.new(id: "b", name: "Medusa", damage: 8, bait: { glory: 1 })
    dungeon = Dungeon.new(boss)
    dungeon.add_room_to_left(room)

    player = Player.new("Player 1")
    player.dungeon = dungeon
    party = Party.new([Hero.new(id: "h", name: "Frail", health: 2, preferred_bait: :power)])
    game = Object.new
    def game.remove_party_from_town(_party); end
    CrawlPhase.resolve_party(game, player, party)
  end

  def test_records_the_crawl_with_deaths_and_grow
    @log.record(3, outcome_with_a_death)
    text = File.read(@path, encoding: "UTF-8")

    assert_match(/Round 3 — Player 1 crawl/, text)
    assert_match(/Succubus.*grows on death/, text)   # flags the grow rule
    assert_match(/\(grow \+1\)/, text)                # grow applied after the death
    assert_match(/Frail took 2 → 0hp DIED/, text)     # the death is in the Succubus room
    assert_match(/1 died, no survivors/, text)
  end

  def test_game_started_writes_a_header
    @log.game_started(["Player 1", "Player 2"])
    assert_match(/New game .* players: Player 1, Player 2/, File.read(@path, encoding: "UTF-8"))
  end
end
