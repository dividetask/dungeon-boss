# Class pseudocode

Language-agnostic pseudocode for every class both clients implement. This is the
behavioural companion to [architecture.md](architecture.md) (responsibilities)
and the rules in [phases.md](phases.md). Names mirror the Ruby reference
implementation in `webapp/lib`; the Android (Kotlin) client should match the
behaviour, not the syntax.

Conventions: `Card.field` = stored data; `↦` = returns; bait types are
`glory|riches|undead|power`.

---

## Data / value objects (immutable)

### Bait
```
ALL = [glory, riches, undead, power]
normalize(name) ↦ lowercase symbol; error if not in ALL
```

### BaitIcons  (a count per bait type; absent = 0)
```
new(map):  store {bait ↦ count} dropping zero/negative, normalized keys
count(bait) ↦ stored count or 0
total()     ↦ sum of counts
contains?(other) ↦ for every (bait,n) in other: count(bait) >= n
shares?(other)   ↦ any bait type appears (>0) in both
```

### Tags  (lowercased set of strings)
```
new(list): lowercase, trim, drop blanks, dedupe
include?(tag) ↦ tag (lowercased) in set
union(other)  ↦ Tags(self ∪ other)
```

### Boss / Room / Upgrade / Hero / AbilityCard
```
Boss:    id, name, damage, bait:BaitIcons, effect:map, tags:Tags, ability_text
Room:    id, name, type, damage, bait, description, effect:map, tags, advanced?
         trap?     ↦ type contains "trap"
         creature? ↦ type contains "monster" or "creature"
Upgrade: id, name, bonus_damage, bait, description, tags;  type = "Upgrade"
Hero:    id, name, health, preferred_bait, courage, tags, ability_text
AbilityCard: id, name, text, effect:map, tags
```
Cards with the same definition are built as **distinct objects** (so two copies
of one card never collapse into one); identity matters in a party/crawl.

### PlacedRoom  (a Room as it sits in a dungeon: base + optional upgrade + grow)
```
new(base_room, upgrade=nil); grow = 0   # grow is permanent (grow-on-death)
damage ↦ base_room.damage + (upgrade ? upgrade.bonus_damage : 0) + grow
bait   ↦ base_room.bait merged with upgrade.bait (counts added)
tags   ↦ base_room.tags ∪ (upgrade ? upgrade.tags : ∅)
effect, type, trap?, creature?, id, name ↦ delegate to base_room
```

---

## Declarative effects (data interpreters)

### Effects.Selector  (matches a room/boss/hero against a `match:` map)
```
new(match): tag, type, bait, preferred_bait   # any subset
matches?(card) ↦ true only if every present dimension holds:
    tag            → card.tags.include?(tag)
    type           → "trap" → card.trap?;  "creature"/"monster" → card.creature?
    bait           → card.bait.count(bait) > 0
    preferred_bait → card.preferred_bait == normalize(preferred_bait)
```

### Effects.Aura  (flat and/or per-point damage to matching cards)
```
new({match, flat=0, per_point=0})
bonus(target, points) ↦ match.matches?(target) ? flat + per_point*points : 0
```

### BossEffect.for(boss) ↦ Spec
```
Spec(effect_map):
    self_per_point = effect_map.self_damage_per_point or 1
    room_bonuses   = effect_map.room_bonuses.map(Aura)
self_bonus(points)       ↦ self_per_point * points         # boss's own +per point
room_bonus(room, points) ↦ Σ room_bonuses.bonus(room, points)
```

### RoomEffect.for(encounter) ↦ Spec   (boss/plain room ↦ do-nothing Spec)
```
Spec(effect_map):
    auras       = effect_map.room_auras.map(Aura)
    party_hits  = effect_map.party_hits.map(PartyHit)     # {match, amount}
    poisons_on_hit?, grows_on_death?, unreducible? = booleans
    next_room_damage = effect_map.next_room_damage (int, 0 = none)
    draws_on_death   = effect_map.draw_on_death ("ability" | "room" | none)
    discard_boost = effect_map.discard_boost (map) or none
aura_bonus(room, points) ↦ Σ auras.bonus(room, points)    # to OTHER rooms
party_hits(alive)        ↦ for each rule, [hero, amount] for each matching alive hero
boostable?               ↦ discard_boost present
```

### AbilityEffect.for(card) ↦ Spec
```
Spec(effect_map): add_damage, unreducible?, zero?, retreat?, draw_rooms
targets_room? ↦ add_damage present OR unreducible? OR zero? OR retreat?  (needs a room)
# retreat = the party turns back AT the targeted room (rooms before it still resolve)
```

### HeroAbility  (per-hero damage modifiers; looked up by hero id)
```
abilities:
  hero_barbarian → HalveRoundedUp   (scope :self)   reduced(_, dmg) = ceil(dmg/2)
  hero_cleric    → ReduceBait(undead, 4) (scope :party)
  hero_mage      → ReduceBait(power, 4)  (scope :party)
  hero_rogue     → ReduceTrap(2)         (scope :party)
  (others)       → Null (scope :self, no reduction)

ReduceBait(bait,n).reduced(enc, dmg) ↦ enc.bait.count(bait)>0 ? max(dmg-n,0) : dmg
ReduceTrap(n).reduced(enc, dmg)      ↦ enc.trap? ? max(dmg-n,0) : dmg

damage_taken(target, encounter, alive_members, base):
    dmg = base
    for member in alive_members:                  # PARTY auras stack across members
        if member.ability.scope == :party: dmg = member.ability.reduced(encounter, dmg)
    if target.ability.scope == :self:    dmg = target.ability.reduced(encounter, dmg)
    ↦ dmg                                          # auras first, then self (halving) last
```

### CrawlModifiers  (per-crawl, keyed by encounter index; reset each crawl)
```
add_damage(i, amount=2): plus[i] += amount
zero!(i); set_damage!(i, v); unreducible!(i); retreat!(i)   # retreat = turn back before encounter i
queries: zero?(i), set?(i), set_value(i), reducible?(i), bonus(i)=plus[i]
         retreating?, retreat_index, retreats_at?(i) = retreat set AND i >= retreat_index
boosted?(i) ↦ any of set/unreducible/zero/plus>0 at i   (enforces once-per-room)
```

---

## Game state

### Deck  (generic draw/discard pile)
```
draw()        ↦ if draw pile empty: shuffle discard pile back in; pop front (or nil)
draw_many(n)  ↦ up to n draws (compact nils)
discard(card); reclaim(card) ↦ remove that exact card from the discard pile (undo)
empty? ↦ both piles empty
```

### Dungeon  (boss + ordered rooms; entrance = index 0, boss on the right)
```
MAX_ROOMS = 5
full? ↦ rooms.size >= MAX_ROOMS
add_room_to_left(room): prepend PlacedRoom(room)            # error if full
replace_room(i, room) ↦ old PlacedRoom (caller discards it; its upgrade is lost)
apply_upgrade(i, upgrade) ↦ previous upgrade (or nil)       # one upgrade per room
encounters() ↦ rooms (left→right) followed by the boss
```

### Player
```
MAX_ROOM_HAND = 6; fields: dungeon, room_hand, ability_hand, points, wounds
add_room_to_hand(room) ↦ false if hand full, else append & true
take_room_from_hand(id) / take_ability_from_hand(id) ↦ remove by id or nil
gain_point(); gain_wound()
```

### Party  (one or more heroes, board order)
```
courage ↦ Σ heroes.courage          # combined, for the courage check
size, lone?, empty?, add(hero), remove(hero)
display_name ↦ name, else heroes joined with " & "
```

### Scoreboard
```
LOSS_WOUNDS=5, WIN_POINTS=10, END_GAME_BONUS=5
eliminated?(p) ↦ p.wounds >= 5
score(p, ender=nil) ↦ p.points - 2*p.wounds + (p is ender ? 5 : 0)
survivors(ps)  ↦ ps with < 5 wounds
over?(ps)      ↦ any p.points >= 10  OR  survivors.size <= 1
winner(ps, ender) ↦ highest score(·, ender) among survivors; tie → the ender; none → ender
standings(ps, ender=nil) ↦ sorted best-first by score(·, ender), eliminated last
```

### Decision  (a pending player choice; pure data)
```
kind ∈ {choose_boss, place_first_room, discard_room, build_room}
fields: player, options (frozen list), allow_skip
prompt ↦ human-readable text for the kind
```

### CardLibrary
```
load(path):
    data = path is a directory ? merge top-level keys of every *.yaml
                               : parse the single file
    expand each list into model objects, repeating `copies` (default 1) as
    DISTINCT instances; assert ids unique within a category
exposes: bosses, rooms, upgrades, advanced_rooms (advanced:true), heroes, ability_cards
```

---

## Logic helpers (no own state)

### BaitCounter
```
enticement(dungeon, bait) ↦ Σ over (rooms + boss) of source.bait.count(bait)
```

### PartyCrawlResolver  (runs a whole party through a dungeon)
```
resolve(party, dungeon, boss_bonus=owner_points, modifiers):
  health = {hero ↦ hero.health}; alive = party.heroes; dead = []; log = []
  poison = {}; delayed = {}; draws = {}      # delayed/draws: see below
  for (encounter, i) in dungeon.encounters:
     break if alive empty
     break if modifiers.retreats_at?(i)          # Retreat: turn back here
     room_index = i;  deaths_here = 0
     poison_tick:   each alive poisoned hero takes poison[hero] (unreducible)
     delayed_tick:  each alive hero with delayed[hero]>0 takes it (unreducible), then clear it
     party_hits:    for [hero, amt] in RoomEffect(encounter).party_hits(alive): hit unreducible
     single_target: base = effective_base(encounter)
                    if base > 0:
                        eff = RoomEffect(encounter)
                        reducible = modifiers.reducible?(i) and not eff.unreducible?   # room-level unreducible
                        target = alive max by (current health, then max health, then order)
                        dealt  = hit(target, base, reducible)
                        if dealt>0 and eff.poisons_on_hit?: poison[target] += 1
                        if dealt>0 and eff.next_room_damage>0: delayed[target] += eff.next_room_damage
     grow:          if RoomEffect(encounter).grows_on_death? and deaths_here>0: encounter.grow += deaths_here
     death_draws:   deck = RoomEffect(encounter).draws_on_death; if deck and deaths_here>0: draws[deck] += deaths_here
  ↦ {participants, log, deaths, dead_heroes, survivors=alive, draws}

effective_base(encounter):
     if modifiers.zero?(i): ↦ 0
     base = modifiers.set?(i) ? modifiers.set_value(i) : encounter.damage
     if encounter is boss: base += BossEffect(boss).self_bonus(boss_bonus)
     else:                 base += BossEffect(boss).room_bonus(encounter, boss_bonus)
     base += Σ over other rooms r: RoomEffect(r).aura_bonus(encounter, boss_bonus)   # Trap Makers/Beast Tamer
     ↦ base + modifiers.bonus(i)                                                     # ability +dmg / boost

hit(hero, amount, reducible):
     dmg = reducible ? HeroAbility.damage_taken(hero, encounter, alive, amount) : amount
     health[hero] -= dmg; log the step; if health<=0: move hero alive→dead, deaths_here++
     ↦ dmg
```

### DungeonSummary  (read-only totals for the quick-sheet)
```
total_damage ↦ Σ (rooms+boss).damage          # printed damage only
bait_totals / all_bait_totals ↦ per-type sums across rooms+boss
```

### RandomAgent  (automated player)
```
choose(decision):
    build_room   → randomly: nothing / place a basic room (add or replace) /
                   attach an upgrade / place an advanced room on a bait-sharing room
    discard_room → a random hand card
    else         → a random option
```

### PartyNamer
```
generate(rng) ↦ "The <Adjective> <Noun>"   (from fixed word lists)
```

### CrawlResolver  *(legacy single-hero resolver; superseded by PartyCrawlResolver, kept for reference)*

---

## Phases  (orchestration only — no rules data)

### SetupPhase
```
deal(game): for each player → deal STARTING_ROOMS (mulligan until ≥1 basic room),
            STARTING_ABILITIES ability cards, and BOSS_CANDIDATES boss candidates
choose_boss(game, player, id): keep that boss, discard the rest, make its Dungeon
place_first_room(game, player, id): move room from hand into the dungeon (must be a Room)
```

### ArrivalPhase
```
run(game): repeat (number of LIVING players) times → draw a hero into town as a lone party
```

### BuildPhase
```
draw_for_each(game): each LIVING player draws DRAW_COUNT(=2) rooms (discard overflow over the cap)
discard(game, player, id) ↦ the discarded room (so the caller can offer undo)
place(game, player, card_id, target):
    upgrade  → dungeon.apply_upgrade(target); discard any replaced upgrade
    advanced → require placed room shares ≥1 bait icon; replace it (discard old)
    basic    → target "new"/nil: add at entrance; else replace room at index
```

### BaitPhase  (combined with Crawl — evaluated per party, see Game)
```
target_for(game, party) ↦ the player whose dungeon the party enters NOW, or nil:
    p = most_enticing_player(game, party)
    ↦ (p and party.courage >= p.points) ? p : nil           # courage vs CURRENT points
most_enticing_player(game, party):
    score each LIVING player by enticement(dungeon, party); ↦ the strict max, else nil (tie)
enticement(dungeon, party) ↦ Σ members: BaitCounter.enticement(dungeon, member.preferred_bait)
```

### CrawlPhase
```
resolve_party(game, player, party, modifiers):
    result = PartyCrawlResolver.resolve(party, player.dungeon, boss_bonus: player.points, modifiers)
            # the resolver stops before encounter `retreat_index` if Retreat was played
    player.gain_point × result.deaths
    player.gain_wound if NOT modifiers.retreating? AND any survivor   # retreaters take no wound
    remove dead heroes from the party; if party empty → remove it from town
    ↦ Outcome(player, party, result, retreated: modifiers.retreating?)
```

### RecruitmentPhase
```
run(game, waiting):
    lones   = waiting.select lone;  parties = waiting.reject lone
    each multi-hero party pulls in one lone hero (prefer a different preferred bait)
    remaining lones pair off into new parties (PartyNamer); odd one out stays alone
    merged-away parties are removed from town
```

---

## Game  (owns players + decks; drives the turn as a decision/crawl state machine)

Stages: `unstarted → setup → ready ↔ building → crawling → (quiet) → ready … → over`.
A player may instead be an **agent** (RandomAgent); its decisions resolve
automatically and never surface.

```
start():
    SetupPhase.deal; enqueue choose_boss + place_first_room per player; stage=setup; auto_advance

start_round():  (only when ready?)
    round++; clear last outcomes
    ArrivalPhase.run; BuildPhase.draw_for_each
    enqueue discard_room (mandatory) + build_room (skippable) per LIVING player
    stage=building; auto_advance

decide(choice_id, target):           # player applies the head decision
    process_head(choice_id, target); auto_advance
process_head:
    pop head decision; error if no choice given and not skippable
    clear undoable-discard unless this is a discard_room
    apply(decision) → SetupPhase/BuildPhase; record undoable discard for discard_room
    resolve_if_idle
auto_advance: while head decision belongs to an agent → agent.choose → process_head

resolve_if_idle:  (when the decision queue is empty)
    setup    → stage=ready
    building → crawl_queue = town (order); waiting=[]; any_entered=false
               stage=crawling; advance_to_next_crawl

advance_to_next_crawl:               # BAIT + CRAWL combined
    while crawl_queue not empty:
        party = crawl_queue.shift
        p = BaitPhase.target_for(self, party)     # re-checks courage vs CURRENT points
        if p: current_crawl=[p,party]; reset modifiers; any_entered=true; RETURN (await Send)
        else: waiting << party
    current_crawl = nil
    if any_entered: finish_turn
    else:           stage = quiet                  # no hero attacked

send_next_party():                   # the "Send party" button
    return if no current_crawl
    agent_pre_crawl(owner)            # automated owner may discard-to-boost
    outcome = CrawlPhase.resolve_party(self, owner, party, modifiers)
    last_outcomes = [outcome]
    apply_death_draws(owner, outcome.result)   # Soul Siphon / Unhallowed Ground
    current_crawl = nil
    if Scoreboard.over?(players): winner = Scoreboard.winner(players, owner); stage=over
    else: advance_to_next_crawl       # next party's courage re-checked vs new points

apply_death_draws(owner, result):    # cards earned by draw-on-death rooms
    result.draws["ability"] times: owner.add_ability_to_hand(ability_deck.draw)
    draw_rooms_for(owner, result.draws["room"])   # respects the room-hand cap

finish_quiet_round():                # "Continue" on a quiet round
    grant each player an ability card; finish_turn
finish_turn: RecruitmentPhase.run(self, waiting); waiting=[]; stage=ready

play_ability(player, card_id, target):     # before/instead of a crawl
    valid only if a current crawl, or quiet (then only non-targeting cards)
    spec = AbilityEffect.for(card)
    if current crawl and target is a room: add_damage / unreducible! / zero! / retreat! at that index
    draw 2 rooms if draw_rooms;  discard the card

boost_room(card_id, room_index):     # dungeon owner discards to boost a room
    return if room already boosted; discard the chosen hand card
    apply discard_boost spec: add_damage(+N) / set_damage / unreducible!

undo_discard(): if can_undo (a discard made, build step pending) →
    reclaim the card from the deck back into hand; re-prompt the discard

queries the UI uses: current_decision, awaiting_decision?, ready?, crawling?,
    quiet?, over?, next_crawl, crawl_mods, living_players, eliminated?(player),
    can_undo_discard?, standings, winner
```
