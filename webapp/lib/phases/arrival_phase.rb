# frozen_string_literal: true

# Arrival phase. Draw one hero card per living player and place them face up in
# town. Town is NOT cleared: heroes stay in town across turns and only leave by
# dying in a dungeon. Eliminated players generate no arrivals. Orchestration only.
class ArrivalPhase
  def self.run(game)
    game.living_players.size.times do
      hero = game.hero_deck.draw
      game.add_to_town(hero) if hero
    end
    game
  end
end
