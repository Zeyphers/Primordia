# Handoff

Start here, then read [`README.md`](../README.md) for what the mod is and
[`PITFALLS.md`](PITFALLS.md) before changing anything in the geometry pipeline.

**State:** builds clean against Minecraft 26.2, 189 tests pass.

---

## This machine

The repo ships `gradle/wrapper/gradle-wrapper.properties` but **no `gradlew` script**, so there is
no wrapper to invoke. Gradle and a JDK were installed separately:

| | |
|---|---|
| JDK 25 | `/opt/homebrew/opt/openjdk@25` — `build.gradle` sets `options.release = 25` |
| Gradle 9.6.1 | `~/dev/tools/gradle-9.6.1/bin/gradle` |

Every command below needs `JAVA_HOME` set inline — it does not persist.

```bash
cd "/Users/jake/AI projects __/Primordia"
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ~/dev/tools/gradle-9.6.1/bin/gradle build
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ~/dev/tools/gradle-9.6.1/bin/gradle test
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ~/dev/tools/gradle-9.6.1/bin/gradle runClient
```

**Not JDK 21 and not Gradle 8.10**, both of which this file used to name and both of which now fail
outright: Loom 1.17.17 publishes its plugin marker for Gradle 9.5+, so 8.10 cannot even resolve the
classpath, and it fails at *configuration* time with a variant-matching wall of text that does not
mention the Gradle version. `gradle/wrapper/gradle-wrapper.properties` still says 8.10 and is also
stale; there is still no `gradlew`, so nothing reads it.

`.agents/AGENTS.md` says never to use `runClient`. **That rule is about the author's Windows box**,
where it opened an invisible window. On macOS it is the normal way to run, and it is how everything
in this session was tested. There is no Primordia profile in the Modrinth App here.

### Dev client mods

`run/` is gitignored, so these are not in the repo and a fresh checkout will not have them. They
were fetched from Modrinth into `run/mods/`:

| mod | version | why this version |
|---|---|---|
| Sodium | 0.6.9 | the build Iris 1.8.7 pins |
| Iris | 1.8.7 | pins a known-good Sodium |
| Resourcify | 1.8.5 | |
| Zoomify | 2.14.4 | newest needs Fabric Loader ≥0.18.0; the project pins 0.16.5 |
| fabric-language-kotlin | 1.12.3 | 1.13.x needs Loader ≥0.16.9; the project pins 0.16.5 |
| YetAnotherConfigLib | 3.7.1 | Zoomify dependency |

Two of those are pinned *down* by `loader_version=0.16.5` in `gradle.properties`. Bumping the loader
would allow current versions of both. `fabric_version` was raised 0.102.0 → 0.116.14 (same 1.21.1
line) because YACL requires ≥0.104.0.

Shaderpacks in `run/shaderpacks/`: BSL 10.1.3, Complementary Reimagined and Unbound r5.8.1. Note
Complementary r5.8.1 is written against a newer Minecraft than 1.21.1 — Iris logs unresolved
uniforms (`inPaleGarden`, `endFlashFactor`) on load. Not fatal; BSL is the cleaner match for testing.

---

## Commands worth knowing

```
/primordia test            30 specimens: 10 archetypes x 3 sizes, named, facing you
/primordia test reload     re-roll in place (keeps the anchor, so sets are comparable)
/primordia test <seed>     reproduce an exact grid
/primordia test walk       drive the gait   ·   /primordia test stand
/primordia info            nearest creature, including the ornament line
```

Posed specimens render at **near tier unconditionally**, bypassing both the distance tier and the
crowd budget. Deliberate: a grid whose far rows are coarser cannot be used to judge a change,
because half the difference on screen would be LOD rather than the generator.

Settings are in **Mods → Primordia** (Mod Menu) or a keybind under Controls → Primordia (unbound by
default). Quality presets run Potato → Ultra; Ultra is sized for the whole test grid at near detail.

---

## What changed here

Eight commits on top of `3482b50 Initial commit`.

**Anatomy.** Six eye styles, seven horn types, five tail shapes, four ear types, frills, beaks,
tusks, bioluminescence. Two-part arachnid bodies (clustered legs, abdomen, knees above the hip) and
`ARACHNID` / `CRUSTACEAN` archetypes. A hinged mandible with teeth.

**Legs no longer web together.** They were physically intersecting — thicker than the gaps between
them — so the union of two overlapping solids was one solid. Hip spacing is reconciled against leg
thickness, feet fan fore-and-aft, and the pole vector goes radial past two pairs. Arachnid
same-side leg pairs intersecting: 360/360 → 12/360.

**Companions.** Tamed creatures can bond (12% on taming, 8% per feed) into wolf-like followers that
defend their owner and can be told to stay. `CreatureEntity` extends `PathAwareEntity`, not
`TameableEntity`, so the three goals are hand-written.

**Spawning.** Superflat and debug worlds generate nothing. Passive weight 80 → 10 and the duplicate
`MONSTER` registration removed — it was spawning the same entity against two independent mob caps
with hostile top-up behaviour.

**Client.** Quality settings screen with LOD presets, per-tier budgets and ranges, mesh cache size
and a normal-smoothing slider. Bioluminescence emits on `entity_translucent_emissive`.

---

## Ecology, phase A

The complaint this addressed: walking into a new area in survival, the carnivores killed every
herbivore within minutes and left beef and bones scattered everywhere. Six causes, diagnosed with
line references in [`ECOLOGY.md`](ECOLOGY.md) — read that first, it also lays out the
four-level design the rest of the ecology is being built to.

Creatures now carry an `energy` budget, hunt only when hungry, give up a chase they cannot win,
leave a carcass rather than item drops when something other than a player kills them, breed in the
wild without the player's involvement, and sleep through half of every day.

`/primordia info` gained a line reporting energy, current state (`fed` / `foraging` / `hunting` /
`asleep` / `carcass`), maturity and generation — energy is server-only and not replicated, so
without that line there is no way to tell a predator that has just eaten from one that is broken.

## Ecology, phases B–F

The whole of [`ECOLOGY.md`](ECOLOGY.md) is now built except dens/nests and the journal UI.

Creatures are **no longer placed by the vanilla spawner at all** — `BiomeModifications.addSpawn` is
gone. Population lives in a per-region ledger (`ecology/region/`), 128 blocks square, persisted with
the save. `RegionMaterialiser` spawns entities from it and `CreatureEntity#checkDespawn` absorbs them
back; `RegionSimulation` advances the regions nobody is in, one in-game day per step; `RegionFounder`
gives a new region founders inherited from its nearest neighbour and 100–300 days of pre-history
before it is ever seen.

Read `RegionMaterialiser`'s class comment before changing anything in that package. The contract is
that a population is either in the record or in the world, never both and never neither, and the
test that guards it (`populationSurvivesAThousandLoadAndUnloadCycles`) is the most important one in
the suite.

**Tuning lives in three files:** `EnergyBudget` for individuals, `RegionSimulation` for populations,
`RegionFounder` for what a new region starts with.

## What has and has not been watched

**None of the ecology has been observed in a live world.** It builds, 94 tests pass including
determinism and the population round trip, and the client boots with the mod loaded — but whether a
valley actually feels balanced, whether founding takes long enough to stutter, and whether trails
appear at a sensible rate are all things only a play session can answer.

Specific things to look at first:

- `/primordia region` in a fresh world, then again after sleeping a few nights, to see whether the
  numbers move at a rate that reads as alive rather than as noise.
- Whether the 3×3 active region block plus `CLUSTER_BUDGET` of 30 keeps the frame rate sane.
- Founding cost. It runs 100–300 simulation steps and is capped at two regions per five-second pass;
  if crossing new terrain stutters, lower `FOUNDINGS_PER_PASS`.
- Whether carcasses read as dead animals. The renderer rolls them 90° about Z and drops them by the
  body's half-width — the transform is geometrically reasoned but has never been looked at.

## Open threads

**The shader faceting is not confirmed fixed.** A real bug was found and fixed — roughly 1 quad in
200 was wound against the normals it carried, which vanilla ignores and shader packs render
inside-out via `gl_FrontFacing` — taking the worst case from ~0.5% to under 0.25%. Whether that is
what the user was seeing was never established. **No screenshot was ever obtained**, despite a
watcher on `run/screenshots/` for most of the session. Getting one is the single highest-value next
step; the smooth-normals slider is a fast discriminator, because if dragging it changes nothing the
cause is not normals at all.

**Saurians drop 18% of their teeth** as genuinely unfittable — the mouth has nowhere to put them.
Everything else keeps 100%. If the gaps look wrong the fix is saurian jaw proportions, not the teeth.

**Nothing about the mouth has been verified by eye.** The jaw, the mandible silhouette and the teeth
are all confirmed geometrically and by test only.

**The quad winding residual is inherent.** A quad bent over a knuckle has two triangle halves that
disagree with each other, and no winding or diagonal choice reconciles that. Reaching zero means
emitting triangles and a custom render layer.

---

## Where the invariants live

Fifteen test classes. The ones that encode something non-obvious:

| test | what it pins |
|---|---|
| `LimbSeparationTest` | limbs do not intersect, and legs get their own blend groups |
| `ThinLimbTest` | nothing is finer than the mesher can resolve; near-tier stays inside budget |
| `JawTest` | the skull is the right way up, the jaw hangs below it and opens downward |
| `ToothClippingTest` | no tooth comes through the opposing jaw with the mouth shut |
| `QuadWindingTest` | quads face the way their shading normals point |
| `PoseWalkTest` | a stationary creature fed a walking speed actually moves its feet |
| `OrnamentTest` | every horn type, tail shape and glow region is reachable |

Read `PITFALLS.md` before adding to these. Several of them were written wrong first, passed,
and hid the bug they existed to catch.


## Found in play

Four bugs the test suite could not have caught, all fixed, all now guarded. Read `PITFALLS.md`
§13–15 — each is a general trap, not a one-off.

| Symptom | Cause |
|---|---|
| One species everywhere, all wanting the same bait | `topUp` gave the whole entity budget to the first lineage in the list; the record held four species and showed one |
| Nothing moved | One lineage means one `NOCTURNALITY`, so the entire population slept and woke on the same tick |
| No large creatures anywhere | `SIZE` was selected downward only, so a region's pre-history pinned every lineage at minimum before the player saw it |
| Everything had a dog muzzle | The muzzle blob sat at a fixed proportion along the skull, so the silhouette was one shape across the whole gene space |

The first was diagnosed from an unrelated system: `TamingPreference` keys favourite food off the
lineage id, so "everything wants sugar cane" was a direct readout of lineage identity that nothing
was designed to provide.

**Hitboxes now cover the head**, capped at twice hip height — Minecraft puts the eye at 85% of the
box and suffocates anything whose eye is inside a block, so an uncapped box would have long-necked
creatures taking damage under every tree. `RegionMaterialiser.place` checks headroom for the animal
it is actually placing, for the same reason.

## Toes sticking together on spider-like creatures — root-caused and fixed

The user's description, which is what made this findable: *"with spider like creatures the toes
verts stick together causing a quad to be stretched between the legs, and it usually happens at the
toes."* That is not a modelling complaint. It names the mechanism — vertices shared between two
legs — and everything below followed from taking it literally.

**Cause: `SkinBinder` ignored `blendGroup`.** A vertex could take real weight from two different
limbs at once. It is then driven by both, follows neither, and the faces around it stretch across
the gap as the gait swings the legs apart. Measured before the fix, at near tier: **8.8% of all
arachnid vertices** were weighted to two different legs (insectoid 9.1%, crustacean 11.9%). After:
**zero, on every archetype.**

The fix is one rule — a vertex may be influenced by the trunk and by *one* limb, never two — and it
is not a new idea, it is the rule `BoneDef.blendGroup` already encodes and that the SDF has always
enforced to stop adjacent legs fusing into webbing. The field held a spider's legs apart and the
skinning tied them back together. The two halves of the pipeline now agree.

### Why the previous session's hop filter could not have worked

`MAX_HOPS` is still there and still earns its place for the ornament case, but it cannot separate
two legs and no tightening of it ever could. **`legA_0` and `legB_0` are both children of a spine
bone, so a creature's opposite legs are two hops apart** — inside any budget that still lets a knee
blend with its own thigh. The 6-hop margin in that constant's doc comment is sound arithmetic for
ornament-to-leg and irrelevant to leg-to-leg. Distance is no help either: on an arachnid the
neighbouring leg genuinely *is* nearby, which is simply what the animal looks like.

### Why no existing test caught it, which is the part worth remembering

- `LimbSeparationTest` asks the **SDF** whether there is material in the gap. There is not. The gap
  is real and correct at every sample. Nothing about the field was ever wrong.
- `SkinBindingTopologyTest` checks that the hop filter does what it was written to do. It does. That
  was never the same claim as "the symptom is gone" — and the gap between those two is precisely
  what this thread kept falling into.

`CrossLimbSkinningTest` asserts on the **shipped bone weights**, which is the only place the defect
is visible. It was confirmed to fail when the new filter is disabled, rather than being assumed to
guard anything (`PITFALLS.md` warns about exactly that, and it nearly happened again here).

## Two things measured and deliberately left unfixed

Both were found by the same diagnostic and neither is what the user reported. Numbers are per 24
specimens at near tier.

**1. Arms weld into the front legs, and into each other.** Once the leg-to-leg skinning was fixed,
every remaining face spanning two limbs was an arm: `arm0R_0 + leg0R_0` (340), `arm0L_0 + leg0L_0`
(337) on arachnids; on crustaceans the worst is `arm0R_1 + arm1R_1` (468). **Zero leg-to-leg pairs
remain.** Arms attach at `u = 0.92 - pair * 0.16` and the foremost legs at `FOREMOST_LEG_U = 0.88`,
so they grow from nearly the same place on the spine.

This is a *mesh topology* weld, not a skinning one, and it needs a different fix. The obvious trick
does not transfer: **arms are not IK-solved.** `CreatureAnimator.swingArm` rotates them from
`skeleton.bindDirection`, so unlike a leg — whose on-screen position comes from `restEffector` via
FABRIK, leaving the bind pose free — moving an arm's bind pose visibly moves the arm. Fixing this
means either separating the attachments (a silhouette change, and so the user's call) or making the
mesher able to hold two surfaces apart inside one cell.

**2. Toe capsules pass within one mesh cell of the neighbouring leg.** Worst same-side toe clearance
is 0.61 cells on crustaceans, and 4 insectoid toes in 182 actually *interpenetrate* the next limb
(worst gap -0.020). `LimbSeparationTest` cannot see this: it builds its pairs from `LimbChain.bones`,
and **the toe bone is appended after `buildLimb` returns, so it is in no chain and is never
examined.**

### A bake-pose spread was tried for this, and it does not work

Worth recording so it is not re-derived. Baking the legs further apart and letting IK pull them back
to the true stance — the trick `BodyPlan.jawRestAngle` already uses for the mouth, and it does work
for legs — cleanly fixed the toe numbers (foot welds 88 → 0 and 36 → 0, interpenetrations 4 → 0,
worst clearance 0.61 → 1.74 cells). It was still reverted, because **it is self-defeating on exactly
the creatures it targets.** Any spread enlarges the bind-pose bounding box; a larger box means a
larger sampling span; `MeshBaker.resolutionFor` is capped at 1.8x the tier's resolution, so past that
cap the cells simply get coarser. It buys sub-cell clearance by making the cell bigger. On thin-limbed
arachnids that is a losing trade, and it showed up as `ThinLimbTest` failing with a whole limb
vanishing from the mesh (`PITFALLS.md` §3) at every spread down to 1x the thinnest limb radius.

**The real fix for both is in the mesher.** `SurfaceNets` stores one vertex per grid cell
(`cellVertex`, one `int` per cell), so two surfaces passing through the same cell do not get one
vertex each — they get one vertex *between* them, and pass 3 stitches quads through it. That is a
structural limit of the extraction, and it is what both remaining items are. Splitting a cell's
vertex by `blendGroup` would fix the class outright, at the cost of a real change to the core mesher
and its quad stitching.

## Still not confirmed on screen

**The fix has not been looked at in a running client.** What it has is an oracle that measures the
reported symptom directly rather than measuring the implementation — 8.8% to 0%, and a guard test
proven to fail without it — which is a materially better position than this thread has been in
before. It is not the same thing as having seen it.

The veil/frill stretch was **not** investigated; the user explicitly deprioritised it ("I don't care
about the veil so much"). The `SkinBinder` analysis in the previous handoff still stands for it: the
binder knows only `plan.bones` and nothing of `plan.blobs`, so an ornament blob far from its anchor
bone can still be misattributed, and the blend-group rule does not save it — if the owner is picked
wrong, the wrong limb group is the one that gets admitted. Dedicated ornament bones remain the fix.
