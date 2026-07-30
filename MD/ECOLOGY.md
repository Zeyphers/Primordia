# The world without you

Design for the ecology layer: what a creature does to the world, what the world does back, and how
both keep happening in places no player is standing.

This supersedes the M2/M4 sketches in [`ROADMAP.md`](ROADMAP.md) and answers the off-screen
simulation question that document defers.

---

## The problem

Walk into an unexplored area in survival and the carnivores kill every herbivore within a few
minutes, leaving beef, leather and bone scattered across the landscape. The intended reading — that
this valley had animals living in it before you arrived — never lands, because the ecosystem visibly
begins the moment you look at it and collapses immediately afterward.

Six causes, all of them structural:

**1. Predators are never satiated.** `CreatureEntity.initGoals()` registers an `ActiveTargetGoal`
over every `CreatureEntity` whose only condition is `theirs.mass < mine.mass * 0.85f`
([CreatureEntity.java:602](../src/main/java/dev/jsz/primordia/entity/CreatureEntity.java:602)).
There is no hunger term, no post-kill cooldown, no notion of having eaten. A carnivore acquires the
nearest smaller creature, kills it, and immediately acquires the next one. Nothing in the code ever
makes a predator stop.

**2. Killing gains the predator nothing.** There is no energy or hunger state on the entity at all.
Predation is a pure sink: prey die, the predator is exactly as it was. A loop with no closing term
does not regulate, it runs.

**3. Every death drops player loot.** `onDeath` calls `SurvivalDrops.dropLoot` unconditionally
([CreatureEntity.java:655](../src/main/java/dev/jsz/primordia/entity/CreatureEntity.java:655)),
whatever the damage source. A creature killed by another creature converts into beef, mutton,
leather and bone on the ground, which nothing consumes. That is literally the free meat and bones —
it is not a symptom of over-predation, it is a second bug sitting on top of it.

**4. Wild creatures never reproduce.** `loveTimer` is assigned in exactly one place: a player
feeding a tamed creature its favourite food
([CreatureEntity.java:440](../src/main/java/dev/jsz/primordia/entity/CreatureEntity.java:440)).
The wild birth rate is zero. Spawning is the only source of animals and predation is a sink, so
every population is monotonically decreasing by construction. Even a perfectly well-behaved predator
would strip a region that cannot replace its losses.

**5. Nothing exists before you arrive.** Spawning is the vanilla natural spawner
(`BiomeModifications.addSpawn`, weight 10, groups of 2–4) and each individual rolls
`Genome.createForBiome`, which picks a **uniformly random archetype** and then tints its colour by
biome. Composition is an independent random draw per animal. A valley is as likely to be seeded with
four carnivores and nothing to eat as with a working food chain. There is no pre-existing balance to
find because nothing computed one.

**6. Populations freeze when unloaded.** Already flagged as an open question in `ROADMAP.md`. Leave
and return and the region is exactly as you left it, which is the opposite of the thing you want to
feel.

There *is* one weak negative feedback: `tickNourishment` starves a creature whose mass exceeds the
local carrying capacity, and `FoodSurvey.carryingCapacity` folds prey density into a carnivore's
ceiling, so predators do eventually thin out once the prey are gone. But it fires every 120 ticks,
caps at 2 damage, and only applies above 1.35× capacity — by the time it bites, the herbivores are
already dead. It punishes the outcome instead of preventing it.

---

## The shape of the fix

The world needs a **memory that is cheaper than entities**. Everything else follows from that.

Entities are the expensive, high-fidelity, transient representation of a population. They should
exist only near the player, and they should be *derived from* a persistent record rather than being
the record themselves. Once the record is the truth and entities are a rendering of it, off-screen
simulation stops being an exotic feature and becomes the default case: the record is a few hundred
bytes and advancing it is arithmetic.

So: four levels, each an order of magnitude cheaper and coarser than the one inside it.

| Level | Scope | Representation | Ticks |
|---|---|---|---|
| **L0 — Ledger** | every region ever touched | numbers on disk | on demand |
| **L1 — Regional sim** | one region | population ODE + mean genome | per in-game day |
| **L2 — Local** | loaded chunks | entities, coarse behaviour | per second |
| **L3 — Individual** | near the player | entities, full behaviour + IK | per tick |

The interesting engineering is not any single level. It is the **handoff** between L1 and L2, where
numbers become animals and animals become numbers again.

---

## L0 — The region ledger

A **region** is 8×8 chunks, 128 blocks square. Large enough to contain a herd's home range, small
enough that a biome boundary is a boundary between regions rather than an average inside one.

Each region carries one record, target size under 1 KB:

```
seed          long          hash(worldSeed, rx, rz) — everything stochastic derives from this
lastStep      long          game day at which this record was last integrated
vegetation    float         standing plant stock, 0..1
lineages      up to 8 of:
    id        int           the lineage id the genome layer already tracks
    mean      byte[COUNT]   the lineage's mean genome, quantised
    variance  float         genetic spread within the lineage
    count     float         population — fractional, deliberately
```

Eight lineages at ~110 bytes each is under 900 B per region. A world with ten thousand visited
regions costs under 9 MB. Stored as a `PersistentState`, keyed by region coordinate.

**Count is a float and must stay one.** Round it at materialisation only, and carry the remainder.
Rounding on every unload means a lineage of 1.4 animals rounds to 1, then to 0, and small
populations quietly evaporate every time the player walks past — which would look exactly like the
bug being fixed.

---

## L1 — The regional simulation

The ledger is integrated in steps of one in-game day. Per step, per lineage:

- **Need** — `count × meanMass × METABOLISM`.
- **Supply** — for plant-eaters, a function of `vegetation` and the biome's productivity; for
  hunters, the summed biomass of lineages they can take, from a predation matrix derived from mean
  mass ratio and `DIET`. This is the same relationship `FoodSurvey` already encodes, evaluated on
  populations instead of on a block sample.
- **Births** — `count × FECUNDITY × min(1, supply/need) × (1 − count/K)`.
- **Deaths** — baseline mortality from `LIFESPAN`, plus predation losses, plus starvation when
  supply falls short.
- **Vegetation** — regrows toward the biome cap, consumed by herbivore biomass scaled by
  `GRAZING_IMPACT`.
- **Selection** — the mean genome shifts along the fitness gradient rather than by simulating
  individuals. Fitness is supply match, predation exposure, and `TEMP_PREFERENCE` /
  `HUMIDITY_PREFERENCE` against the biome. Variance widens under drift and narrows under strong
  selection. `Mutation`'s per-gene plasticity governs how far each locus can move per step, so a
  lineage stays visually recognisable for the same reason it does in the entity-level breeding path.
- **Speciation** — variance past `SPECIATION_DISTANCE` splits the lineage into two divergent means.
  Same threshold, same meaning, so a clade tree assembled from the ledger and one assembled from
  observed births are the same tree.
- **Extinction** — count below a threshold frees the slot. Extinction must be real and permanent, or
  nothing that happens has weight.
- **Migration** — a fraction of each lineage bleeds into the four neighbouring regions, weighted by
  how well the neighbour's biome suits that genome. Terrain that a creature cannot cross damps the
  weight.

Migration is the single most important line in the whole design. It is what makes a successful
lineage spread across a continent, what makes a mountain range a genuine barrier, and what turns
walking a long way into a biogeographical observation rather than a series of unrelated random draws.

**Integration is lazy.** A region advances when it loads, when a neighbour needs its migration
input, or when the player scans it. `lastStep` says how many days are owed. Cap the catch-up at ~90
steps and approximate anything beyond that by relaxing toward the equilibrium the ODE would reach,
so returning to a save after months does not stall the server. Cap steps *per server tick* too — a
player crossing the world in an elytra should not integrate two hundred regions in one frame.

**It must be deterministic.** Same record plus same step count must give the same result, every
time, or the simulation cannot be reproduced, tested, or trusted. All randomness comes from the
region seed mixed with the step index. Note that `tickBreeding` currently reaches for
`ThreadLocalRandom` ([CreatureEntity.java:719](../src/main/java/dev/jsz/primordia/entity/CreatureEntity.java:719));
that has to go.

---

## L2 — The local ecosystem

**Materialisation.** When a region's chunks enter entity-ticking range, the ledger places the
animals: for each lineage, a number of individuals proportional to `count`, capped by a per-region
entity budget of around 24, with genomes sampled as `mean + N(0, variance)`. Placement respects
habitat — a den site, water, cover.

This inverts the current spawner's role. `BiomeModifications.addSpawn` stops inventing creatures;
a custom spawner places the ones the ledger says are already there.

**Dissolution.** When the chunks unload, surviving entities write back: new count, new mean genome,
new variance. Anything the player killed is simply gone from the count. Tamed and named creatures
are exempt — they are individuals, persisted as entities, and not part of any population.

That contract is the whole design in one sentence: **the ledger is the truth and entities are a
rendering of it.** Cull six herbivores from a valley and the ledger drops by six. Come back in a
week and it has recovered, or hasn't, depending on what else lives there.

Systems that live at this level:

- **Energy and hunger** per entity, spent on movement and growth, scaled by mass and `METABOLISM`.
- **Carcasses.** A creature killed by another creature leaves a carcass, not item drops. The killer
  feeds from it over ~40 seconds; omnivores scavenge; it rots to a bone pile and then to nothing.
  Item drops happen only when the *player* makes the kill. This deletes the free-meat problem
  outright and opens a scavenging niche the `DIET` gradient can evolve into.
- **Vegetation consumption.** `GrazeGoal` currently animates eating without consuming anything.
  Grazing removes the block and debits the region's `vegetation`, which is what makes overgrazing a
  real boom-bust rather than a scripted one.
- **Home range.** A den position per group, and a radius. Animals return to it. Without this,
  `WanderAroundFarGoal` diffuses everything into everything and predators meet prey at the maximum
  possible rate.

---

## L3 — The individual

Full behaviour near the player, where it can actually be watched.

- **Hunts fail.** Today, `ActiveTargetGoal` into `MeleeAttackGoal` is a death sentence with a delay.
  It should be stalk → chase → grapple, with a stamina budget on both animals: the predator aborts
  when spent and pays for the failed chase, the prey escapes if it outlasts. Target success rate
  25–35%. Failed hunts, not predator mortality, are the main reason real prey populations persist —
  and a chase you watch a herbivore *win* is far better television than a guaranteed kill.
- **Herds.** `SOCIABILITY` already exists and does nothing. Grouping, shared vigilance, and young
  kept to the middle.
- **Flight distance** from `FEAR`, rather than the current fixed 12-block check in
  `FleeLargerCreatureGoal`.
- **Sleep**, from `NOCTURNALITY`. Underrated: a predator asleep through the day is a predator not
  killing anything for half the cycle, and it reads instantly as an animal with a life rather than a
  hunting routine.

### The five brakes on predation

Each is cheap, none is sufficient alone, and they compose into a stable system.

| Brake | Mechanism |
|---|---|
| Satiation | `energy` in 0..1; hunting gated on `energy < 0.55`; a full meal is worth ~0.6 |
| Carcasses | a kill has to be *eaten*, over time, in one place — not banked instantly |
| Failed hunts | chase stamina on both sides; most attempts end with the prey alive |
| Reproduction | wild breeding on energy, maturity, cooldown from `FECUNDITY` and local density |
| Rarity | the ledger enforces a trophic pyramid; carnivore biomass capped at ~15% of herbivore |

And the sixth, which does more than any of them: a predator that is asleep, out of range, or on the
far side of its territory is not hunting. Most regulation in real ecosystems is availability, not
appetite.

---

## Biogeography — what makes an infinite world worth walking

On first touch, a region is **founded and then pre-aged**.

Founders are inherited from the nearest already-recorded region, drifted in proportion to distance
and to biome dissimilarity; with no neighbour on record, they are generated from the biome as
`createForBiome` does now, but as a **trophic pyramid** rather than eight independent rolls — a
plant-eating base, a middle, and at most one or two hunters.

Then the region is integrated forward by 100–300 days before the player ever sees it. It costs one
loop over an ODE and it buys the entire premise: you crest a ridge into a valley whose animals are
visibly related to each other, adapted to that biome, already balanced, and already different from
the ones two thousand blocks behind you — because they have been diverging from the same founders
this whole time.

The emergent results are the point:

- **Clines.** Fauna change gradually with distance, because migration mixes neighbours.
- **Barriers.** A mountain range or ocean damps migration, so the far side genuinely diverges.
- **Endemics.** An island receives founders once and then evolves alone.
- **Extinctions and recolonisations.** A crashed region is repopulated by whatever migrates in
  next, which will not be what was there before.

None of that is authored. It falls out of migration plus drift plus a persistent record.

---

## Making it legible

A world that moves without you is worth nothing if the player cannot perceive that it moved.

- **Scanner, region mode** — read the region rather than an animal: lineages present, populations,
  trend, vegetation state.
- **Field journal** — the clade tree, with where each branch lives.
- **Ambient tells** — bone piles at old kill sites, cropped grass and trails around a den, young
  animals in a growing population, carcasses in a crashing one.
- **The returning-player test.** Leave a region for three in-game days and come back. If nothing
  observable has changed, the simulation is not tuned loudly enough. That is the acceptance
  criterion for the whole feature, and it should be checked by hand every time the numbers move.

---

## Sequencing

**Phase A — Stop the massacre. Built.** Hunger and satiation, carcasses in place of drops, hunts
that fail, wild breeding, sleep. Entirely local, no new persistence, no save-format commitment.
This alone changes the felt experience of walking into a new area, and it is worth shipping even if
nothing below it is ever built. What landed, against the six causes above:

| Cause | Fix |
|---|---|
| Predators never satiated | `energy` in [0,1]; every hunting goal gated on `CreatureEntity#wantsToHunt` |
| Killing gained nothing | carcasses, and `FeedOnCarcassGoal` — a kill has to be walked back to and eaten |
| Every death dropped loot | `SurvivalDrops#killedByPlayer` forks it; creature kills leave a body |
| No wild reproduction | `tickWildBreeding` — fed, mature adults enter breeding condition on their own |
| Nothing regulates the chase | `EnergyBudget#chaseBudgetTicks` bounds pursuit; failure costs energy and a cooldown |
| — | `RestGoal`: `NOCTURNALITY` takes half of every animal's day out of the food web |

Two brakes were added during implementation rather than designed up front, both because a test
caught the mechanism failing to close:

- **A minimum prey size** (`EnergyBudget#MIN_PREY_MASS_RATIO`). A large predator gets almost nothing
  from very small prey, so it could never eat its way above the hunger threshold and kept killing —
  the original bug arriving through the arithmetic instead of the logic, with no error to find.
  `EnergyBudgetTest#oneWholePreyAnimalSatisfiesItsPredator` is what pins it.
- **A post-kill cooldown** (`CreatureEntity#onKilledOther`). A predator is still under its hunger
  threshold at the instant it kills, so the targeting goal acquired the next animal on the following
  tick and the feeding goal — which will not run while a target is set — never got a turn. The
  predator walked away from every body it made.

Still outstanding in Phase A's area: grazing feeds the animal but does not yet consume the block, so
plant food is effectively infinite and herbivore populations are bounded only by density and
predation. Consuming vegetation needs somewhere to debit it to, which is Phase B.

**Phase B — The ledger. Built.** `RegionRecord` / `LineageRecord` in a `PersistentState`, keyed by
128-block region. `RegionMaterialiser` turns counts into entities and `CreatureEntity#checkDespawn`
turns them back. `BiomeModifications.addSpawn` is gone — the vanilla spawner no longer places
creatures at all.

**Phase C — The regional simulation. Built.** `RegionSimulation` integrates a region one in-game day
per step: plant supply, predation through the same size window the entities use, births, deaths,
selection on the mean genome, drift, speciation, extinction, and migration to the four neighbours.
Deterministic from the region seed; catch-up is capped at 90 steps with a relaxation beyond that.

**Phase D — Founding and pre-ageing. Built.** `RegionFounder` inherits founders from the nearest
recorded region — drifted by distance and climate gap — or seeds a trophic pyramid where there is
nothing to inherit from, then runs 100–300 days of pre-history before the region is ever seen.

**Phase E — World impact. Partly built.** Grazing consumes the block and debits the region's
vegetation; heavy traffic wears grass to dirt to path. All of it goes through `WorldImpact`, which
holds a narrow allow-list of touchable blocks and a per-chunk change budget. **Dens, burrows and
nests are not built** — they need new blocks and block entities, and none of the promises above
depend on them.

**Phase F — Legibility. Built.** `/primordia region` reads out the ledger; the genome scanner used
on nothing surveys the region instead of an animal, with population trends. **The field journal UI
is not built** — the data is all there and reachable, but a windowed clade tree is a separate piece
of work.

---

## What pins each phase

This repo's test culture is invariants over fuzzed input, and this system needs it more than the
geometry did, because an ecology failure looks like a plausible ecology.

| Test | Invariant |
|---|---|
| `EnergyBudgetTest` | **built** — thresholds are coherently ordered, every genome gets hungry on a plausible clock, one whole prey animal satisfies its predator, grazing outpaces its own drain, pursuit is bounded, generations are short enough to observe |
| `RegionLedgerTest` | **built** — all six below, in one class |
| ↳ population round trip | a thousand materialise/absorb cycles do not drift the population by a thousandth |
| ↳ determinism | the same record integrated the same number of steps gives identical output, gene for gene |
| ↳ stability | 500 days from any seed neither empties the region nor runs away |
| ↳ founding | no region is founded with hunters and nothing to hunt, and its fauna already fit the climate |
| ↳ migration | a lineage seeded at one end of a row reaches the other, and diverges on the way |
| ↳ shared prey window | the regional model eats exactly what the entity layer would hunt |

The round-trip test was written first and is the one to keep. A population that leaks a few percent
on every load/unload cycle is invisible in play, passes every other test here, and empties the world
over a few hours of exploration — the exact failure mode `PITFALLS.md` is a catalogue of.

---

## Open questions

- **Region size.** 128 blocks is a guess. Too small and a herd's range spans four ledgers; too large
  and a mountain, a lake and a desert average into one meaningless number.
- **Vanilla animals.** Cows and sheep are currently prey
  ([CreatureEntity.java:619](../src/main/java/dev/jsz/primordia/entity/CreatureEntity.java:619))
  but are not in the ledger, so they are an unmodelled food source that never depletes. Either bring
  them into the accounting or stop hunting them.
- **Multiplayer.** Two players in different regions integrating simultaneously; the ledger needs to
  be server-authoritative and locked per region.
- **Where the numbers live.** Phase C introduces a lot of constants. One config file, as
  `ROADMAP.md` already insists.
