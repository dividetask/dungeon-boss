# Dungeon Boss — Android App

The production client, written in **Kotlin** with **Jetpack Compose**. It loads
the same card data ([`../data/cards.yaml`](../data/cards.yaml)) and implements
the same rules and class design as the spec in [`../docs/`](../docs/) and the
reference engine in [`../webapp/lib/`](../webapp/lib/).

Because Android (Kotlin) and the web app (Ruby) cannot share source, the shared
contract is the **`docs/` specification** and the **`data/cards.yaml`** file.
The classes below mirror the responsibilities in
[`../docs/architecture.md`](../docs/architecture.md) one-for-one.

## Layout

```
android/
├── build.gradle.kts / settings.gradle.kts      # Gradle (Kotlin DSL)
└── app/
    ├── build.gradle.kts                         # module config + cards.yaml sync task
    └── src/
        ├── main/
        │   ├── assets/cards.yaml                # synced copy of /data/cards.yaml
        │   └── java/com/dungeonboss/
        │       ├── model/      # Bait, BaitIcons, Boss, Room, Upgrade, Hero,
        │       │               #   AbilityCard, PlacedRoom (immutable value objects)
        │       ├── data/       # CardLibrary (loads cards.yaml via SnakeYAML)
        │       ├── game/       # Deck, Dungeon, Player, Party, PartyNamer,
        │       │               #   Scoreboard, Decision, Game, BaitCounter,
        │       │               #   DungeonSummary, HeroAbility, CrawlResolver,
        │       │               #   PartyCrawlResolver, Agent, RandomAgent,
        │       │               #   LogicAgent, DungeonForecast
        │       ├── game/phases/# Setup, Arrival, Build, Bait, Crawl, Recruitment
        │       └── ui/         # Compose screen (analogue of the web view +
        │                       #   CardPresenter): Theme, CardViews, GameScreen,
        │                       #   GameViewModel, MainActivity
        └── test/java/com/dungeonboss/           # JUnit engine-fidelity tests
```

The **`ui/`** package is the only part that depends on Android/Compose; the
`model/`, `data/` and `game/` packages are plain Kotlin and are unit-tested on
the JVM.

## Faithfulness to the engine

The engine is a direct port of `../webapp/lib/`, preserving the same rules:

- **Cards are identity objects, not Kotlin `data class`es.** The deck expands a
  definition's `copies` into *distinct* instances, and party/crawl logic keys off
  object identity — so two copies of the Mage in one party stay independent
  (matching the Ruby objects). This is covered by a unit test.
- **Phases** are classes that orchestrate one step each; Arrival/Bait/Crawl run
  automatically, Setup/Build wait on a `Decision`.
- **Player 1 is you; Players 2…N are `LogicAgent`s** resolved behind the scenes,
  exactly as in the web app. `LogicAgent` reads its strategy from
  `assets/ai_logic.yaml` and scores each `Decision` with a tie-break chain of
  comparators (static + `DungeonForecast` simulations); a simpler `RandomAgent`
  remains as a baseline. See [../docs/ai.md](../docs/ai.md).
- Resolution uses damage, health, bait icons, preferred bait, **hero abilities**
  (Barbarian/Cleric/Mage/Rogue) and **courage / parties / recruitment**. Ability
  cards and boss/room ability text are ignored in v1, as per the docs.

## The screen

The Compose UI mirrors the phone-sized web layout:

- A top bar (**New game**) and a round/stage line.
- A row of **boss summaries** (boss name, total damage, bait totals, points /
  wounds) — tap one to view that player's dungeon, tap again to return to yours.
- The **Town** of heroes and parties (afraid heroes show grouped under their
  generated party name).
- **Your hand**, drawn as cards with art glyphs, colored bait pips and stats.
- The viewed **dungeon board** — entrance on the left, boss on the right — which
  doubles as the crawl track: during a crawl the active encounter is highlighted,
  hero chips below the board update their HP and show 💀 on death, and a textual
  log records points and wounds.
- A pinned bottom **advance bar**: *Build nothing* / *Send party* / *Next turn*
  depending on the phase.

Build is two-step, like the web app: tap a hand card, then tap a slot — a room
adds at the entrance or replaces a room; an upgrade attaches to a room.

## Building & running

Open the `android/` folder in **Android Studio** (Giraffe or newer) and run the
`app` configuration on a device/emulator (minSdk 24). Android Studio downloads
the Gradle wrapper distribution, the Android Gradle Plugin and the Compose
dependencies on first sync.

From the command line (with the Android SDK installed and `ANDROID_HOME` set):

```sh
cd android
./gradlew :app:assembleDebug      # build the APK
./gradlew :app:testDebugUnitTest  # run the engine unit tests
```

> Note: this checkout ships `gradle/wrapper/gradle-wrapper.properties` but not the
> wrapper JAR/scripts (they require resolving the Android Gradle Plugin to
> generate). Android Studio creates them on import, or run
> `gradle wrapper --gradle-version 8.9` in an environment with access to Google's
> Maven repository.

The `app` module has a `syncCards` task wired into `preBuild` that copies
[`../data/cards.yaml`](../data/cards.yaml) into `app/src/main/assets/` so the
bundled data never drifts from the canonical file.

## Scope

v1 ignores all ability text and ability cards (see
[`../docs/README.md`](../docs/README.md)).
