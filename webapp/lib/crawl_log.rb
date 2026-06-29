# frozen_string_literal: true

require "fileutils"
require "time"
require_relative "room_effect"

# Appends a human-readable record of every crawl to a log file, so a run can be
# replayed and debugged after the fact (e.g. "did a hero actually die in the
# Succubus room, and did its grow bonus apply?"). Pure infrastructure for the
# web app — the game engine does not depend on it; Game calls #record when a
# logger is supplied and is silent otherwise.
class CrawlLog
  def initialize(path)
    @path = path
    FileUtils.mkdir_p(File.dirname(path))
  end

  # Note the start of a fresh game so its crawls are grouped under a header.
  def game_started(player_names)
    append("=" * 60)
    append("New game @ #{timestamp} — players: #{player_names.join(', ')}")
    append("=" * 60)
  end

  # Record one resolved crawl (a CrawlPhase::Outcome). The dungeon state is read
  # AFTER resolution, so grow bonuses earned this crawl are already reflected.
  def record(round, outcome)
    player = outcome.player
    lines = []
    lines << "-" * 60
    lines << "Round #{round} — #{player.name} crawl @ #{timestamp}" \
             " (now #{player.points} pts, #{player.wounds} wounds)"
    lines.concat(dungeon_lines(player.dungeon))
    lines.concat(party_lines(outcome.result))
    lines.concat(step_lines(outcome.result))
    lines.concat(summary_lines(outcome))
    append(lines.join("\n"))
  end

  private

  def timestamp
    Time.now.strftime("%Y-%m-%d %H:%M:%S")
  end

  def dungeon_lines(dungeon)
    lines = ["  Dungeon (entrance → boss):"]
    dungeon.rooms.each { |room| lines << "    #{room_line(room)}" }
    boss = dungeon.boss
    lines << "    BOSS #{boss.name} [dmg #{boss.damage}]"
    lines
  end

  def room_line(room)
    note = +"dmg #{room.damage}"
    note << " (grow +#{room.grow})" if room.grow.positive?
    note << " {grows on death}" if RoomEffect.for(room).grows_on_death?
    "#{room.name} [#{room.type}] #{note}"
  end

  def party_lines(result)
    members = result.participants.map { |h| "#{h.name}(#{h.health}hp)" }
    ["  Party: #{members.join(', ')}"]
  end

  def step_lines(result)
    return ["  Steps: (no damage dealt)"] if result.log.empty?

    lines = ["  Steps:"]
    result.log.each do |s|
      died = s[:died] ? " DIED" : ""
      lines << "    #{s[:encounter].name}: #{s[:hero].name} took #{s[:damage]}" \
               " → #{s[:health_after]}hp#{died}"
    end
    lines
  end

  def summary_lines(outcome)
    result = outcome.result
    survivors = result.survivors.map(&:name)
    draws = result.draws.reject { |_, n| n.zero? }
    lines = ["  Result: #{result.deaths} died, " \
             "#{survivors.empty? ? 'no survivors' : "survivors: #{survivors.join(', ')}"}"]
    lines << "    Retreated before finishing the dungeon." if outcome.retreated
    lines << "    Cards drawn on death: #{draws.map { |d, n| "#{n} #{d}" }.join(', ')}" unless draws.empty?
    lines
  end

  def append(text)
    File.open(@path, "a:UTF-8") { |f| f.puts(text) }
  end
end
