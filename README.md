# Primordia

Spore's core loop, in Minecraft. Creatures are not modelled — they are **grown from a genome**,
every time, at runtime. A gene vector becomes a skeleton, the skeleton becomes a signed distance
field, the field becomes a mesh, and the mesh walks on procedurally animated legs solved with
inverse kinematics. Populations then breed, mutate, diverge into lineages, and reshape the world
they live in.

Nothing about a creature is authored. There are no models, no textures, no animation files.

---

## Status

**Milestone 1 is implemented and in-game verified**: a genome spawns a creature that is generated,
meshed, coloured, and walks with full procedural IK. Taming, saddling, and riding work. Predation,
diet-based temperament, foraging, and breeding are functional.

**Verified:** compiles clean against Minecraft 1.21.1, and all 76 tests pass — covering genome
serialisation, body-plan generation over hundreds of random genomes, bind-pose skinning
correctness, IK convergence, mesh validity, knee stability, limb separation and intersection,
archetype coverage, ornament reachability, the hinged jaw, dentition, and quad winding.

New here: start with [`HANDOFF.md`](HANDOFF.md) for the current state and how to build and run, and
read [`docs/PITFALLS.md`](docs/PITFALLS.md) before changing the geometry pipeline — it lists the
failure modes that leave the body plan valid and the tests green while the creature is wrong.

**Current deployed version:** `primordia-0.1.2.jar` in Modrinth App profile.

| Milestone | What it delivers | State |
|---|---|---|
| **M1 — It lives** | Genome → skeleton → SDF → mesh → IK walk cycle | **done, verified in-game** |
| **M2 — It eats** | Food webs, hunger, foraging, predation, death | **in progress** |
| M3 — It evolves | Breeding, selection pressure, lineage divergence | genetics layer done, ecology loop partially wired |
| M4 — It changes things | Grazing, burrowing, nest building, terrain effects | not started |
| M5 — You study it | Genome scanner item, field journal UI, lineage tree | scanner implemented |

The genetics engine (`Mutation`) is complete and tested ahead of M3, because the genome format
is the hardest thing to change later — everything is keyed by it.

---

## Requirements

- **JDK 21** — required by Minecraft 1.21.
- Minecraft **1.21.1**, Fabric Loader 0.16+, Fabric API.

This machine has no system JDK, so `setup-toolchain.ps1` installs a portable Temurin JDK 21 and
Gradle into `dev\tools\` without needing admin rights. Run it once:

```powershell
powershell -ExecutionPolicy Bypass -File setup-toolchain.ps1
```

## Build

```powershell
$env:JAVA_HOME = 'C:\Users\jacob.szczepaniak\dev\tools\jdk-21'
C:\Users\jacob.szczepaniak\dev\tools\gradle-8.10\bin\gradle.bat build
```

The built jar lands in `build/libs/`. To deploy, copy it to the Modrinth App profile:

```
C:\Users\jacob.szczepaniak\AppData\Roaming\ModrinthApp\profiles\Primordia\mods\
```

Previous versions are archived in the `old_versions/` subfolder within that directory.
Launch the game from the **Modrinth App** (not `gradle runClient`, which spawns an
invisible window on this machine).

## Try it

In-game, with cheats on:

| Command | Effect |
|---|---|
| `/primordia spawn` | One random creature |
| `/primordia spawn 10` | Ten of them |
| `/primordia spawn 5 1234` | Five, reproducibly, from seed 1234 |
| `/primordia info` | Full breakdown of the nearest creature's genome and body |
| `/primordia breed` | Cross the two nearest creatures; reports genetic divergence |
| `/primordia mutate` | Spawn a mutated clone of the nearest creature |
| `/primordia clear 32` | Remove creatures within 32 blocks |
| `/primordia stats` | Mesh cache and bake queue depth |

`/primordia breed` is the interesting one — run it repeatedly on a pair and their descendants to
watch a lineage drift, and eventually to see `(NEW LINEAGE)` when the offspring diverge past the
speciation threshold.

---

## How a creature is made

```
Genome            60 scalars in [0,1] + seed + lineage id      genome/Genome
   │
   ▼  BodyPlanBuilder — "development"
BodyPlan          bones, IK chains, SDF blobs, palette         body/BodyPlan
   │
   ├──────────────► Skeleton         posable bone hierarchy    skeleton/Skeleton
   │                    │
   │                    ▼  CreatureAnimator: gait → body → spine → IK
   │                 posed skeleton + skinning matrices
   │
   ▼  BodySdf — capsules per bone, ellipsoids per blob, smooth-unioned
Signed distance field                                          sdf/BodySdf
   │
   ▼  SurfaceNets — dual contouring to quads
   ▼  Pattern — vertex colours     ▼  SkinBinder — bone weights
MeshData          bind-pose mesh, cached per genome            mesh/MeshData
   │
   ▼  SkinnedMesh + CreatureRenderer
pixels
```

### Why these choices

**SDF bodies, not stitched parts.** Recombining vanilla mob parts would have been far quicker,
but limbs would intersect the torso instead of growing out of it. A smooth-union of capsules
means a leg *fairs into* a hip, and the same code handles a genome with six legs as easily as
two.

**Surface Nets, not marching cubes.** Surface Nets emits quads, which is exactly what Minecraft's
entity render layers consume — no custom render layer, no degenerate-triangle workaround. It also
needs no 256-entry triangulation table, and its dual vertices sit at the average of the edge
crossings, so a coarse grid still reads as smooth. Marching cubes at the same resolution looks
visibly faceted.

**Meshed once per genome, not raymarched.** A raymarched SDF would be analytically perfect but
fights Minecraft's renderer over depth, lighting and shadows, and breaks under Iris. Baking to a
mesh means lighting, shadows, fog and other mods all work with no special cases. Meshes are cached
by genome, so a herd of siblings costs one bake.

**Vertex colours, not textures.** Colour is baked into the mesh, so there is no texture atlas and
no UV unwrap, and every creature in the world shares one flat white texture — one render layer,
one batch, however many species are on screen.

**FABRIK, not analytic IK.** Limbs have two *or* three segments depending on the genome. FABRIK
handles both with one implementation and no trigonometry. Its one weakness — no opinion about
which way a knee bends — is fixed by rolling the solved chain about the hip-to-foot axis until
the mid joint lines up with the limb's pole vector.

**World-space foot plants.** A planted foot is stored in absolute world coordinates, so the body
moves over a foot that genuinely does not move. This is the whole difference between a walk cycle
and a skating animation.

**Feet ease into plants; they are never assigned.** Three transitions used to be hard jumps: the
gait phase flipping to stance before a swing had quite finished, a creature stopping mid-swing with
a foot in mid-air, and a leg coming back into reach after being stretched straight. All three read
as the foot snapping. Stance and stop now converge exponentially onto the plant, and IK targets are
clamped into the leg's reach so the knee never locks in the first place.

**The ground probe rejects surfaces you could not step onto.** Returning the first solid block
found scanning downward makes a foot latch onto the side of a wall or tree trunk as the creature
walks past — the limb appears glued to it. A candidate surface must have clear headroom above it,
which a wall column never does, and must be within step height of the creature's own feet. When
neither holds, the probe reports no ground and the leg hangs naturally beside the obstacle.

**Slopes bend the spine, not just the root.** Rotating the whole creature rigidly to match the
terrain reads as a plank tilting. 55% of the pitch is applied at the root and the rest is
distributed along the spine, weighted toward the middle of the back, since the shoulders and hips
are anchored by the limbs. Creatures whose legs give no front-to-rear spread to measure from
(bipeds) sample the terrain ahead and behind instead.

**Legs are fitted to the ground, not the other way round.** `BodyPlanBuilder` picks a hip height,
pins the foot to y = 0, and derives bone lengths from the curve between them. A creature therefore
*cannot* generate with legs too short to stand on — the failure mode is designed out rather than
validated against.

---

## Performance

Everything expensive scales with an LOD tier (`mesh/LodTier`), chosen per creature per frame from
camera distance **and** a global per-frame budget. Overflow spills down a tier, so a screen full
of creatures degrades gracefully instead of tanking the frame rate.

| Tier | Distance | Mesh resolution | Budget | IK | Animation rate |
|---|---|---|---|---|---|
| NEAR | < 12 m | 40 | 8 | full | every frame |
| MID | < 28 m | 26 | 18 | full | 30 Hz |
| FAR | < 56 m | 15 | 40 | canned cycle | 12 Hz |
| DISTANT | beyond | 9 | unlimited | canned cycle | 5 Hz |

Resolution is a *floor*, not a fixed value. `MeshBaker` raises it until sampling cells are smaller
than the creature's thinnest limb, because a limb narrower than one cell falls between samples and
disappears from the mesh entirely — the leg is not coarse, it is absent. The lift is capped at
1.8× the tier value and at `MAX_RESOLUTION`, so one slender genome cannot demand a grid that takes
seconds to bake. Worst observed near-tier mesh is ~8,600 quads.

Meshes bake on daemon worker threads and are never built on the render thread — a creature that
is still baking is simply skipped for a frame, and coarser tiers finish first so a new species
pops in low-detail immediately and sharpens a moment later.

To scale up or down, change `BUDGET` and `RESOLUTION` in `mesh/LodTier`. That is the single tuning
point; nothing else needs to change.

---

## Tests

```powershell
C:\Users\jacob.szczepaniak\dev\tools\gradle-8.10\bin\gradle.bat test
```

The suite fuzzes hundreds of random genomes against the invariants that have no visual tell:

- **`BodyPlanTest`** — every genome yields a valid skeleton: parents precede children, limbs are
  mirrored, feet sit on the ground plane, every leg has slack for IK to bend into, development is
  deterministic (which the mesh cache depends on).
- **`SkeletonTest`** — a zero pose produces exactly identity skinning matrices. If this breaks,
  every creature renders subtly deformed and it looks like the generator just made an odd animal.
- **`FabrikTest`** — the solver reaches reachable targets, never stretches a bone, keeps the hip
  pinned, honours the pole vector, and is stable across repeated solves.
- **`MeshBakeTest`** — meshes are non-empty and internally consistent, skin weights sum to 1,
  normals are unit length, and skinning at bind pose reproduces the baked mesh exactly.
- **`GenomeTest`** — serialisation round-trips, malformed codes degrade to null rather than
  throwing, mutation never escapes [0,1], and offspring really are closer to their parents than to
  strangers.
- **`JawTest`** — the mandible is a hinged bone parented to the skull, in a blend group of its
  own, baked slightly ajar so there is a seam to open along, and it swings *down*. The sign of
  that rotation is one character and a jaw closing up into the braincase looks, from most camera
  angles, merely odd — so the test measures the hinge in the skull's own frame rather than in
  world space, where head pitch would swamp it.
- **`ToothClippingTest`** — no tooth comes through the jaw it closes against once the mouth shuts.
  Only observable in the closed pose: the mesh is baked with the mouth wide open, where every tooth
  sits harmlessly in the gap. It reads baked mesh vertices rather than recomputing where teeth
  ought to be, because an earlier version that recomputed them reported no clipping while creatures
  were visibly full of it.
- **`QuadWindingTest`** — quads face the way their shading normals point. Invisible in vanilla,
  which lights entities from the vertex normal alone; shader packs branch on `gl_FrontFacing` and
  render a mis-wound quad inside-out.
- **`PoseWalkTest`** — a stationary creature fed a walking speed still moves its feet, which is what
  the `/primordia test` grid depends on.
- **`OrnamentTest`** — every horn type, tail shape and glow region is reachable from some genome
  and meshes. These traits have no invariant of their own to break — a hornless creature is
  perfectly valid — so the thing worth testing is that no branch of the generator is unreachable,
  which nothing else in the suite would notice. It also pins the arachnid body plan: eight legs
  clustered on a front segment, an abdomen behind them, and knees above the hip.

---

## Known rough edges

- `CreatureRenderer` uses vanilla's `textures/misc/white.png`. If a future Minecraft version drops
  that asset, creatures render magenta; ship a 1×1 white PNG and repoint `TEXTURE`.
- Version-sensitive API surface, if porting off 1.21.1: `EntityType.Builder#build(String)` takes a
  `RegistryKey` from 1.21.2, `writeCustomDataToNbt` is renamed in 1.21.5, and the
  `EntityAttributes.GENERIC_*` constants lose their prefix in 1.21.2.
- Creatures do not spawn naturally yet — they exist only via commands. That lands with M2.
- Skinning is CPU-side. Moving it to a GPU vertex shader with a bone palette is the next big
  performance lever if creature counts need to go well past the current budget.
