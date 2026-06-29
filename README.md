# Dungeon Boss

A competitive card game in which each player builds a dungeon of **room cards**
behind a **boss card** and lures wandering **heroes** to their doom. Kill a hero
in your dungeon to score a point; let one escape and take a wound.

## Repository layout

| Path        | What it is                                                          |
|-------------|---------------------------------------------------------------------|
| `docs/`     | The shared specification — rules, phases, card schema, class design. |
| `data/`     | `cards/`: the canonical card definitions (bosses, rooms, heroes, abilities), shared by all clients. |
| `webapp/`   | Sinatra reference implementation (Ruby) — a rules-testing harness.   |
| `android/`  | The production Android client (placeholder).                         |
| `CLAUDE.md` | Project guidelines.                                                  |

Start with **[docs/README.md](docs/README.md)**.

## Scope: version 1

v1 resolves the game using only damage, health, and bait. All **ability text**
and **ability cards** are ignored (the fields exist for forward compatibility).

## Quick start (web app)

```sh
cd webapp
ruby -Itest -e 'Dir["test/**/*_test.rb"].each { |f| require File.expand_path(f) }'  # run engine tests
bundle install && bundle exec rackup                                                 # run the app
```
