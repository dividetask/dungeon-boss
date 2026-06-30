# Class pseudocode

Language-agnostic pseudocode for every class both clients implement. This is the
behavioural companion to [architecture.md](architecture.md) (responsibilities)
and the rules in [phases.md](phases.md). Names mirror the Ruby reference
implementation in `webapp/lib`; the Android (Kotlin) client should match the
behaviour, not the syntax.

Conventions: `Card.field` = stored data; `↦` = returns; bait types are
`glory|riches|undead|arcane`.

---

## Data / value objects (immutable)

### Bait
```
ALL = [glory, riches, undead, arcane]
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
Hero:    id, name, icon, preferred_bait, starting_hp, hp_level_increment,
         self_damage_multiplier, party_damage_reduction,
         party_damage_reduction_level_increment,
         damage_bait_filter (bait|nil), damage_room_type_filter (type|nil), tags
         level = 0     # the ONE mutable field: set at arrival, +1 on crawl survival,
                       # persists until the hero dies (heroes are distinct instances)
         max_hp          ↦ starting_hp + floor(level * hp_level_increment)
         courage         ↦ 1 + level                 # uniform base 1 (interpretation)
         party_reduction ↦ party_damage_reduction
                           + floor(level * party_damage_reduction_level_increment)
AbilityCard: id, name, text, effect:map, tags
```
Cards with the same definition are built as **distinct objects** (so two copies
of one card never collapse into one); identity matters in a party/crawl.

### PlacedRoom  (a Room in a dungeon: base + optional upgrade + grow + level)
```
new(base_room, upgrade=nil); grow = 0; level = 0   # grow & level are permanent
damage ↦ base_room.damage + (upgrade ? upgrade.bonus_damage : 0) + grow
bait   ↦ base_room.bait merged with upgrade.bait AND any bait granted by
         room-card upgrades (counts added)
tags   ↦ base_room.tags ∪ (upgrade ? upgrade.tags : ∅)
upgrade_with(room_card): bait += room_card.bait; level += room_card.advanced? ? 2 : 1
         # the gameplay effect of `level` is built on another branch
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

### HeroAbility  (per-hero damage modifiers; read from the hero's DATA fields)
```
# No per-id code. Each hero contributes from its own fields:
#   party aura  → party_damage_reduction (levelled), gated by the two filters
#   self mult   → self_damage_multiplier, applied to the hero itself only

filter_matches?(hero, encounter):           # does the party aura apply here?
    if hero.damage_bait_filter and encounter.bait.count(filter) == 0: ↦ false
    if hero.damage_room_type_filter and not encounter is room of that type: ↦ false
    ↦ true                                   # null filters are ignored; both set ⇒ both must hold

party_reduction(member, encounter):
    filter_matches?(member, encounter) ? member.party_reduction : 0   # uses member.level

damage_taken(target, encounter, alive_members, base):
    dmg = base
    for member in alive_members:             # PARTY auras stack across members
        dmg = max(dmg - party_reduction(member, encounter), 0)
    dmg = ceil(dmg * target.self_damage_multiplier)   # self mult last, rounded up
    ↦ max(dmg, 0)                            # auras first, then self multiplier last
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

### Dungeon  (boss + 5 ordered slots; entrance = slot 0, boss on the right)
```
SLOTS = 5; slots = [nil, nil, nil, nil, nil]   # each slot is empty or a PlacedRoom
empty_slots ↦ indices where slots[i] is nil
occupied    ↦ PlacedRooms in slot order (skips empties)
full? ↦ no empty slots
place_room(i, room) ↦ old PlacedRoom or nil:               # empty fills, occupied replaces
    old = slots[i]; slots[i] = PlacedRoom(room); ↦ old     # caller discards old + its upgrade
apply_upgrade(i, upgrade) ↦ previous upgrade (or nil)      # one dedicated upgrade per room
upgrade_room_with(i, room_card): slots[i].upgrade_with(room_card)   # bait + level
encounters() ↦ occupied (left→right) followed by the boss
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
LOSS_WOUNDS=5, WIN_POINTS=10
eliminated?(p) ↦ p.wounds >= 5
score(p)       ↦ p.points - 2*p.wounds
survivors(ps)  ↦ ps with < 5 wounds
over?(ps)      ↦ any p.points >= 10  OR  survivors.size <= 1
winner(ps, ender) ↦ highest score among survivors; tie → the ender; none → ender
standings(ps)  ↦ sorted best-first, eliminated last
```

### Decision  (a pending player choice; pure data)
```
kind ∈ {choose_boss, place_first_room, discard_rooms, build_room}
fields: player, options (frozen list), allow_skip
  # place_first_room / build_room options include the target SLOT (0..4)
  # discard_rooms is a skippable multi-select of 0..2 room cards
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
  health = {hero ↦ hero.max_hp}; alive = party.heroes; dead = []; log = []  # full LEVELLED HP
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
    build_room    → randomly: nothing / place a room into a random slot (empty or
                    replacing) / attach a dedicated upgrade / spend a room card to
                    upgrade a placed room (bait + level)
    discard_rooms → randomly discard 0..2 hand cards
    else          → a random option
```

### PartyNamer
```
generate(rng) ↦ "The <Adjective> <Noun>"   (from fixed word lists)
```

### CrawlResolver  *(legacy single-hero resolver; superseded by PartyCrawlResolver, kept for reference)*

---

## Phases  (orchestration only — no rules data)

### SetupPhase  (Boss Selection + First Room Selection)
```
deal(game): for each player → deal STARTING_ROOMS (mulligan until ≥1 basic room),
            STARTING_ABILITIES ability cards, and BOSS_CANDIDATES boss candidates
choose_boss(game, player, id): keep that boss, discard the rest, make its Dungeon
place_first_room(game, player, id, slot): move room from hand into dungeon slot 0..4
                                          (must be a Room)
```

### ArrivalPhase
```
run(game): repeat (number of LIVING players) times → draw a hero into town as a lone
           party; set the drawn hero's level = floor(game.round / 4)
```

### DiscardPhase
```
discard(game, player, ids[0..2]) ↦ the discarded rooms (caller may offer undo)
                                    # 0..2 cards; discarding nothing is allowed
```

### DrawPhase
```
draw_for(game, player, discarded_count):
    player draws (1 + discarded_count) rooms (discard overflow over MAX_ROOM_HAND)
```

### BuildPhase
```
place(game, player, card_id, target):
    upgrade_card      → dungeon.apply_upgrade(slot); discard any replaced upgrade
    upgrade_with_room → dungeon.upgrade_room_with(slot, room_card); discard the spent card
                        #   grants bait + level (effect of level: another branch)
    advanced room     → empty slot: place; occupied slot: require ≥1 shared bait, replace (discard old)
    basic room        → place into the chosen slot (empty fills, occupied replaces/discards)
```

### EnticePhase  (was BaitPhase — evaluated per party, see Game)
```
target_for(game, party) ↦ the player whose dungeon the party enters NOW, or nil:
    p = most_enticing_player(game, party)
    ↦ (p and party.courage >= p.points) ? p : nil           # courage vs CURRENT points
most_enticing_player(game, party):
    score each LIVING player by enticement(dungeon, party); ↦ the strict max, else nil (tie)
enticement(dungeon, party) ↦ Σ members: BaitCounter.enticement(dungeon, member.preferred_bait)
```

### AbilityPhase  *(TODO — not built)*
```
# Target design: a priority loop. Players take turns playing one ability or passing;
# any ability played re-opens the window to ALL players, until everyone passes in a row.
# For now the Game's existing pre-Gauntlet interaction (play_ability / boost_room) stands in.
```

### GauntletPhase  (was CrawlPhase)
```
resolve_party(game, player, party, modifiers):
    result = PartyCrawlResolver.resolve(party, player.dungeon, boss_bonus: player.points, modifiers)
            # the resolver stops before encounter `retreat_index` if Retreat was played
    player.gain_point × result.deaths
    player.gain_wound if NOT modifiers.retreating? AND any survivor   # retreaters take no wound
    remove dead heroes from the party; if party empty → remove it from town
    ↦ Outcome(player, party, result, retreated: modifiers.retreating?)
            # result.survivors are recorded for levelling in RechargePhase
```

### RechargePhase  (was RecruitmentPhase; now also abilities + levelling)
```
run(game, waiting, attacked_owners, crawl_survivors):
    # 1. ability cards — each LIVING player NOT in attacked_owners draws one ability card
    # 2. party consolidation (unchanged):
    lones   = waiting.select lone;  parties = waiting.reject lone
    each multi-hero party pulls in one lone hero (prefer a different preferred bait)
    remaining lones pair off into new parties (PartyNamer); odd one out stays alone
    merged-away parties are removed from town
    # 3. levelling — each hero in crawl_survivors: hero.level += 1
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
    round++; clear last outcomes; attacked_owners={}; crawl_survivors={}
    ArrivalPhase.run                          # arriving heroes get level floor(round/4)
    enqueue discard_rooms (skippable, 0..2) + build_room (skippable) per LIVING player
    stage=building; auto_advance

decide(choice_id, target):           # player applies the head decision
    process_head(choice_id, target); auto_advance
process_head:
    pop head decision; error if no choice given and not skippable
    clear undoable-discard unless this is a discard_rooms
    apply(decision) → SetupPhase/DiscardPhase/BuildPhase
    if discard_rooms: record undoable discard; DrawPhase.draw_for(player, #discarded)
    resolve_if_idle
auto_advance: while head decision belongs to an agent → agent.choose → process_head

resolve_if_idle:  (when the decision queue is empty)
    setup    → stage=ready
    building → crawl_queue = town (order); waiting=[]; any_entered=false
               stage=crawling; advance_to_next_crawl

advance_to_next_crawl:               # ENTICE + GAUNTLET, per party
    while crawl_queue not empty:
        party = crawl_queue.shift
        p = EnticePhase.target_for(self, party)   # re-checks courage vs CURRENT points
        if p: current_crawl=[p,party]; reset modifiers; any_entered=true; RETURN (await Send)
        else: waiting << party
    current_crawl = nil
    if any_entered: finish_turn
    else:           stage = quiet                  # no party crawled

send_next_party():                   # the "Send party" button
    return if no current_crawl
    agent_pre_crawl(owner)            # automated owner may discard-to-boost (Ability step)
    outcome = GauntletPhase.resolve_party(self, owner, party, modifiers)
    last_outcomes = [outcome]
    attacked_owners << owner; crawl_survivors += outcome.result.survivors   # for Recharge
    apply_death_draws(owner, outcome.result)   # Soul Siphon / Unhallowed Ground
    current_crawl = nil
    if Scoreboard.over?(players): winner = Scoreboard.winner(players, owner); stage=over
    else: advance_to_next_crawl       # next party's courage re-checked vs new points

apply_death_draws(owner, result):    # cards earned by draw-on-death rooms
    result.draws["ability"] times: owner.add_ability_to_hand(ability_deck.draw)
    draw_rooms_for(owner, result.draws["room"])   # respects the room-hand cap

finish_quiet_round():                # "Continue" on a quiet round
    finish_turn                       # Recharge grants ability cards to un-attacked players
finish_turn: RechargePhase.run(self, waiting, attacked_owners, crawl_survivors)
             waiting=[]; stage=ready

play_ability(player, card_id, target):     # before/instead of a crawl
    valid only if a current crawl, or quiet (then only non-targeting cards)
    spec = AbilityEffect.for(card)
    if current crawl and target is a room: add_damage / unreducible! / zero! / retreat! at that index
    draw 2 rooms if draw_rooms;  discard the card

boost_room(card_id, room_index):     # dungeon owner discards to boost a room
    return if room already boosted; discard the chosen hand card
    apply discard_boost spec: add_damage(+N) / set_damage / unreducible!

undo_discard(): if can_undo (a discard made, build step pending) →
    reclaim the discarded card(s) from the deck back into hand (and return the
    cards drawn for them); re-prompt the discard

queries the UI uses: current_decision, awaiting_decision?, ready?, crawling?,
    quiet?, over?, next_crawl, crawl_mods, living_players, eliminated?(player),
    can_undo_discard?, standings, winner
```
