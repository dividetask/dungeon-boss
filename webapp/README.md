# Dungeon Boss — Web App (Sinatra)

A small [Sinatra](https://sinatrarb.com/) app that exists to **test and
prototype the game rules**. It is the reference implementation of the engine
described in [`../docs/`](../docs/). All rules live in `lib/`; the web layer in
`app.rb` only drives the engine and renders state.

**Player 1 is you; Player 2 is a random computer opponent.** When the rules call
for a choice (which boss to keep, which room to place), the app prompts you and
applies what you pick; Player 2's choices are made automatically by a
`RandomAgent` and never prompt.

## Layout

```
webapp/
├── app.rb              # Thin Sinatra web layer
├── config.ru           # Rack entry point
├── Gemfile
├── Rakefile            # `rake test`
├── lib/                # The game engine (one class per concept)
│   ├── bait.rb             # valid bait types
│   ├── bait_icons.rb       # immutable per-bait counts
│   ├── boss.rb / room.rb / hero.rb / ability_card.rb   # card value objects
│   ├── card_library.rb     # loads ../data/cards.yaml
│   ├── deck.rb             # draw/discard/shuffle pile (reshuffles when empty)
│   ├── dungeon.rb          # boss + ordered rooms (max 5); add/replace; encounters
│   ├── dungeon_summary.rb  # total damage + bait totals for a dungeon
│   ├── party.rb            # a group of heroes (lone hero = party of one)
│   ├── party_namer.rb      # generates party names
│   ├── party_crawl_resolver.rb # runs a party through a dungeon (targeting)
│   ├── upgrade.rb          # upgrade card; placed_room.rb wraps room + upgrade
│   ├── scoreboard.rb       # win/loss + final scoring
│   ├── decision.rb         # a pending player choice (boss / room / build)
│   ├── player.rb           # dungeon, hand, score, assigned heroes
│   ├── bait_counter.rb     # dungeon enticement for a bait
│   ├── crawl_resolver.rb   # runs one hero through one dungeon
│   ├── random_agent.rb     # automated player (random choices)
│   ├── card_presenter.rb   # web-only: card art glyphs + bait pips (presentation)
│   ├── game.rb             # owns players/decks, runs the phases
│   └── phases/             # SetupPhase, ArrivalPhase, BuildPhase,
│                           #   BaitPhase, CrawlPhase
└── test/               # Minitest engine tests
```

See [`../docs/architecture.md`](../docs/architecture.md) for the responsibility
of each class.

## Running the tests

The engine tests need only Ruby and Minitest (no bundler):

```sh
ruby -Itest -e 'Dir["test/**/*_test.rb"].each { |f| require File.expand_path(f) }'
```

or, with the dev gems installed:

```sh
bundle install
bundle exec rake test
```

## Running the web app

```sh
bundle install
bundle exec rackup        # then open http://localhost:9292
```

Then:

The layout is phone-sized: one dungeon is shown at a time with a column of
**player summaries on the right** (boss name, total damage, bait totals). Tap a
summary to view that player's dungeon; tap it again (or your own) to return to
yours. A single **advance bar** is pinned to the bottom.

- **New game** — creates a you-vs-computer game and deals the opening cards.
- The app prompts you to **choose a boss** (1 of 2) and **place a first room**
  by tapping the card (Player 2 chooses randomly behind the scenes).
- Your hand is always shown; the computer's hand is hidden.
- The bottom bar shows **Build nothing** during a build, or **Next turn** when a
  round can start.
- **Build**: each turn you draw **two** cards and must **discard one**, then
  optionally play one — tap a hand card, then tap a slot in your dungeon. A
  **room** can be added at the entrance or replace a room; an **upgrade** (a
  rarer card, ~1 per 3 rooms) attaches to a room for bonus damage or bait (one
  upgrade per room; replacing a room loses its upgrade). A dungeon holds at most
  **5 rooms**, and your hand at most **6** cards. If your opening hand has no
  room, it is mulliganed until it does.
- After building, you **send parties in one at a time** with the bottom button.
  Each press resolves one party: the view switches to the dungeon being crawled
  and the party's heroes walk its rooms — each room hits the highest-health
  member. Each death scores a point; one wound per surviving party. Survivors
  stay together in town; a party only leaves when all members die.
- **Courage / parties.** A party enters a dungeon only if its combined courage
  is at least that dungeon owner's points; otherwise it's afraid. In the
  **Recruitment** step after the crawl, afraid heroes band into named parties
  (shown grouped in town) and try again on a later turn.
- **Winning.** A player with 5 wounds is eliminated; the game ends at 10 points
  or when all but one player is out. Final score is points − 2 × wounds.

Cards are drawn as actual cards (frame, art glyph, stats, colored bait pips) via
`lib/card_presenter.rb`. The art is currently emoji; see the note below on real
image assets.

## Scope

v1 ignores all ability text and ability cards (see
[`../docs/README.md`](../docs/README.md#scope-version-1)).
