![Primordia](https://cdn.modrinth.com/data/cached_images/5b3d6c167245848de481471729e64fb9cb9a7e2f.png)

---

**Every creature in this mod is grown from a genome at runtime. There are no models, no textures,
and no animation files.** A gene vector becomes a skeleton, the skeleton becomes a signed distance
field, the field becomes a mesh, and the mesh walks on legs solved with inverse kinematics. Then
they breed, mutate, diverge into lineages, and reshape the world they live in.

You will not meet the same animal twice.

---

## Install

**You need:**

| | |
|---|---|
| Minecraft | **26.2** |
| Mod loader | **Fabric Loader 0.19.3** or newer |
| Fabric API | **0.155.2+26.2** or newer |
| Java | **21+** (any modern Minecraft launcher installs this for you) |

**Steps:**

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 26.2.
2. Download **Fabric API** ([Modrinth](https://modrinth.com/mod/fabric-api) ·
   [CurseForge](https://www.curseforge.com/minecraft/mc-mods/fabric-api)) — Primordia will not
   load without it.
3. Download `primordia-2.0.0-26.2.jar` from the [Releases page](../../releases/latest).
4. Put **both** jars into your `mods` folder:
   - Windows — `%APPDATA%\.minecraft\mods`
   - macOS — `~/Library/Application Support/minecraft/mods`
   - Linux — `~/.minecraft/mods`
   - Using the Modrinth App or Prism? Open your instance's own mods folder instead.
5. Launch the game with the Fabric profile.

That's it. Creatures generate in the world on their own — no configuration needed.

> **Optional:** install [Mod Menu](https://modrinth.com/mod/modmenu) to get an in-game settings
> screen. Without it the mod still works; you would just edit `config/primordia.json` by hand.

### Something went wrong?

| Symptom | Cause |
|---|---|
| Game crashes on startup | Fabric API missing, or the wrong Minecraft version. Primordia is 26.2 only. |
| Creatures are bright magenta | A texture failed to load — report it, this is a bug. |
| Frame rate drops with many creatures | Lower the quality preset (see **Settings** below). Start with `Low`. |
| No creatures anywhere | They spawn like normal animals — give it time, and try a grassy biome. Or use `/primordia spawn` with cheats on. |

---

## Your first five minutes

Creatures spawn naturally, but with cheats enabled you can skip the waiting:

| Command | Effect |
|---|---|
| `/primordia spawn` | One random creature |
| `/primordia spawn 10` | Ten of them |
| `/primordia info` | Full breakdown of the nearest creature — genome, body, ecology |
| `/primordia test` | A grid of test creatures, side by side, for comparison |

Craft a **Field Guide** to record what you find. Every creature you study is filed by lineage, and
the guide fills in as you learn more about each one.

Then try `/primordia breed` on two creatures, and again on their offspring, and again. Watch the
lineage drift — and eventually see `(NEW LINEAGE)` when the descendants have diverged far enough
to count as something else.

---

## What's new in 2.0

- **Creature voices are synthesised, not sampled.** Every call is generated from the animal's own
  anatomy: pitch from body mass, timbre from head and neck length, growl from aggression. No
  creature borrows a vanilla animal's sound any more.
- **Far more variety.** The generator always offered around a thousand ornament combinations but
  produced roughly fifteen of them; the founder draw has been rebuilt so the whole space actually
  appears. Ornament amplitude nearly doubled, and traits that showed up on 0.4% of creatures now
  show up on 8%.
- **Places have their own fauna.** Body plans are now chosen from the climate and the trophic role
  they are being founded for, so a desert's grazers are not a rainforest's, and a creature's shape
  tells you what it eats.
- **Feet.** Hooves, pads, splayed toes, talons, webbing, chitin tips.
- **Creatures stand up to four blocks tall** rather than being squashed to 2.5.
- Many mesh and animation fixes: holes at the hips, ornament dragged by leg bones, shading on
  crouching creatures, arms growing through legs, and wading through shallow water instead of
  swimming in it.

---

## Settings

Everything lives in `config/primordia.json` — client-only, so two players can run wildly different
settings and still see the same animals. With **Mod Menu** installed it is all editable in-game.

**Quality presets** — `Potato`, `Low`, `Balanced` (default), `High`, `Ultra`. Start here; the
individual sliders below are for when a preset is nearly right.

| Preset | Near/Mid/Far/Distant resolution | Near/Mid/Far budget | Max resolution | Mesh cache | Full-IK tier |
|---|---|---|---|---|---|
| Potato | 20 / 14 / 10 / 6 | 3 / 6 / 15 | 40 | 192 | NEAR only |
| Low | 28 / 18 / 12 / 8 | 5 / 12 / 25 | 56 | 256 | NEAR only |
| Balanced | 40 / 26 / 15 / 9 | 8 / 18 / 40 | 80 | 384 | NEAR + MID |
| High | 48 / 34 / 20 / 12 | 16 / 36 / 80 | 96 | 768 | NEAR + MID |
| Ultra | 60 / 44 / 28 / 16 | 32 / 72 / 160 | 128 | 1536 | NEAR + MID + FAR |

**The ones most worth knowing about:**

- **Voxel mode** (`voxelMode`, on by default): snaps creatures to a world-aligned block grid so
  they read as built from blocks rather than sculpted. Turn it off for smooth bodies.
- **Voxel size** (`voxelPixels`, 0.25–2 pixels): how big those blocks are. Smaller is finer and
  costs *cubically* more to build — 0.5 is eight times the work of 1.0. If creatures with many
  thin legs look webbed together, this is the setting that fixes it.
- **Creature voices** (`creatureVoices`) and **voice volume** (`creatureVoiceVolume`).
- **Sharp shading** (`sharpShading`): faceted rather than smooth. Renderer-only; the mesh is
  identical either way.
- **Emissive glow** (`emissiveGlow`): whether bioluminescent creatures actually light up.

<details>
<summary><b>Every other setting</b></summary>

- **Creatures per tier** (`nearCreatures`, `midCreatures`, `farCreatures`): how many creatures
  draw at full tier detail before the rest spill down to the next tier.
- **Tier distances** (`nearDistance`, `midDistance`, `farDistance`): camera distance, in blocks,
  where each tier gives way to the next.
- **Tier resolutions** (`nearDetail`, `midDetail`, `farDetail`, `distantDetail`): Surface Nets
  cells along a creature's longest axis at each tier. A floor, not a fixed value: the mesher raises
  it for any genome whose limbs are thinner than one cell, up to `detailCeiling`.
- **Detail ceiling** (`detailCeiling`): hard cap on cells per axis regardless of how thin a
  creature's limbs are, so one slender genome can't demand a bake that takes seconds.
- **Mesh cache size** (`meshCacheSize`): distinct baked meshes held in memory before eviction.
- **Full-IK tier** (`fullIkTier`): the coarsest tier that still runs full inverse kinematics;
  tiers below it fall back to a canned animation cycle.
- **Normal smoothing** (`normalSmoothing`, 0-100%): blend between the analytic SDF gradient and
  the mesh's own vertex normals.
- **Flat face colour** (`flatFaceColour`): each face is coloured with the mean of its corners
  instead of interpolating a gradient across it. Matches the look of voxel mode; wrong on a smooth
  body, where it flattens what should read as curved shading.

</details>

---

## All commands

| Command | Effect |
|---|---|
| `/primordia spawn` | One random creature |
| `/primordia spawn 10` | Ten of them |
| `/primordia spawn 5 1234` | Five, reproducibly, from seed 1234 |
| `/primordia spawn 5 cave_crawler 1234` | Five of one archetype, from seed 1234 |
| `/primordia test` | A grid of test creatures for visual comparison |
| `/primordia test walk` / `stand` | Toggle whether the test grid animates |
| `/primordia info` | Full breakdown of the nearest creature's genome, body and ecological state |
| `/primordia collect 48` | File every creature within radius into your field guide |
| `/primordia region` | The ledger for the region you are standing in: populations, trends, vegetation |
| `/primordia breed` | Cross the two nearest creatures; reports genetic divergence |
| `/primordia mutate` | Spawn a mutated clone of the nearest creature |
| `/primordia clear 32` | Remove creatures within 32 blocks |
| `/primordia stats` | Mesh cache and bake queue depth |

---

# For developers

Everything below is about how the mod is built and why. None of it is needed to play.

## Building from source

**Requirements:** JDK 25 (required by Minecraft 26.2) and Gradle 9.6.1+ — Loom 1.17.17 declares a
plugin API version of 9.5.0 and fails variant resolution against anything older.

On Windows, `setup-toolchain.ps1` installs a portable Temurin JDK 25 and Gradle 9.6.1 into
`dev\tools\` without needing admin rights, and is safe to re-run:

```powershell
powershell -ExecutionPolicy Bypass -File setup-toolchain.ps1
```

Then:

```bash
JAVA_HOME=/path/to/jdk-25 gradle build
```

`build` runs `compileJava`, bundles resources, and executes the test suite before producing
`build/libs/primordia-<version>.jar` and a matching sources jar.

**Development tools**, none of which need the game running:

| Task | What it does |
|---|---|
| `gradle editor` | Creature editor on `http://127.0.0.1:8090/` — tweak a genome, see the body |
| `gradle voiceLab` | Voice synthesiser on `http://127.0.0.1:8091/` — tweak a voice, hear it |
| `gradle diversityReport` | Samples 5000 founders and reports how much variety the generator actually produces |

Each snapshots its classes at launch, so restart after a code change.

## How a creature is made

```
Genome            88 scalars in [0,1] + seed + lineage id      genome/Genome
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
entity render layers consume: no custom render layer, no degenerate-triangle workaround. It also
needs no 256-entry triangulation table, and its dual vertices sit at the average of the edge
crossings, so a coarse grid still reads as smooth. Marching cubes at the same resolution looks
visibly faceted.

**Meshed once per genome, not raymarched.** A raymarched SDF would be analytically perfect but
fights Minecraft's renderer over depth, lighting and shadows, and breaks under Iris. Baking to a
mesh means lighting, shadows, fog and other mods all work with no special cases. Meshes are cached
by genome, so a herd of siblings costs one bake.

**Vertex colours, not textures.** Colour is baked into the mesh, so there is no texture atlas and
no UV unwrap, and every creature in the world shares one flat white texture: one render layer,
one batch, however many species are on screen.

**FABRIK, not analytic IK.** Limbs have two *or* three segments depending on the genome. FABRIK
handles both with one implementation and no trigonometry. Its one weakness (no opinion about
which way a knee bends) is fixed by rolling the solved chain about the hip-to-foot axis until
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
walks past: the limb appears glued to it. A candidate surface must have clear headroom above it,
which a wall column never does, and must be within step height of the creature's own feet. When
neither holds, the probe reports no ground and the leg hangs naturally beside the obstacle.

**Slopes bend the spine, not just the root.** Rotating the whole creature rigidly to match the
terrain reads as a plank tilting. 55% of the pitch is applied at the root and the rest is
distributed along the spine, weighted toward the middle of the back, since the shoulders and hips
are anchored by the limbs. Creatures whose legs give no front-to-rear spread to measure from
(bipeds) sample the terrain ahead and behind instead.

**Legs are fitted to the ground, not the other way round.** `BodyPlanBuilder` picks a hip height,
pins the foot to y = 0, and derives bone lengths from the curve between them. A creature therefore
*cannot* generate with legs too short to stand on. The failure mode is designed out rather than
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
disappears from the mesh entirely: the leg is not coarse, it is absent. The lift is capped at
1.8× the tier value and at `MAX_RESOLUTION`, so one slender genome cannot demand a grid that takes
seconds to bake. Worst observed near-tier mesh is ~8,600 quads.

Meshes bake on daemon worker threads and are never built on the render thread. A creature that
is still baking is simply skipped for a frame, and coarser tiers finish first so a new species
pops in low-detail immediately and sharpens a moment later.

To scale up or down, change `BUDGET` and `RESOLUTION` in `mesh/LodTier`. That is the single tuning
point; nothing else needs to change.

---

## Tests

```bash
gradle test
```

The suite fuzzes hundreds of random genomes against the invariants that have no visual tell:

- **`BodyPlanTest`**: every genome yields a valid skeleton: parents precede children, limbs are
  mirrored, feet sit on the ground plane, every leg has slack for IK to bend into, development is
  deterministic (which the mesh cache depends on).
- **`SkeletonTest`**: a zero pose produces exactly identity skinning matrices. If this breaks,
  every creature renders subtly deformed and it looks like the generator just made an odd animal.
- **`FabrikTest`**: the solver reaches reachable targets, never stretches a bone, keeps the hip
  pinned, honours the pole vector, and is stable across repeated solves.
- **`MeshBakeTest`**: meshes are non-empty and internally consistent, skin weights sum to 1,
  normals are unit length, and skinning at bind pose reproduces the baked mesh exactly.
- **`GenomeTest`**: serialisation round-trips, malformed codes degrade to null rather than
  throwing, mutation never escapes [0,1], and offspring really are closer to their parents than to
  strangers.
- **`JawTest`**: the mandible is a hinged bone parented to the skull, in a blend group of its
  own, baked slightly ajar so there is a seam to open along, and it swings *down*. The sign of
  that rotation is one character, and a jaw closing up into the braincase looks, from most camera
  angles, merely odd. So the test measures the hinge in the skull's own frame rather than in
  world space, where head pitch would swamp it.
- **`ToothClippingTest`**: no tooth comes through the jaw it closes against once the mouth shuts.
  Only observable in the closed pose: the mesh is baked with the mouth wide open, where every tooth
  sits harmlessly in the gap. It reads baked mesh vertices rather than recomputing where teeth
  ought to be, because an earlier version that recomputed them reported no clipping while creatures
  were visibly full of it.
- **`QuadWindingTest`**: quads face the way their shading normals point. Invisible in vanilla,
  which lights entities from the vertex normal alone; shader packs branch on `gl_FrontFacing` and
  render a mis-wound quad inside-out.
- **`PoseWalkTest`**: a stationary creature fed a walking speed still moves its feet, which is what
  the `/primordia test` grid depends on.
- **`OrnamentTest`**: every horn type, tail shape and glow region is reachable from some genome
  and meshes. These traits have no invariant of their own to break (a hornless creature is
  perfectly valid), so the thing worth testing is that no branch of the generator is unreachable,
  which nothing else in the suite would notice. It also pins the arachnid body plan: eight legs
  clustered on a front segment, an abdomen behind them, and knees above the hip.

---

## Known rough edges

- `CreatureRenderer` bundles its own `assets/primordia/textures/misc/white.png` rather than
  depending on vanilla's, after that asset moved during the 26.2 port; if a future version relocates
  or removes it again, creatures render magenta until `TEXTURE` is repointed.
- The Preservation Case block is not in this release: its dedicated block class, container
  behaviour, and assets were pulled from `1.0.0` and will return once finished. `SimpleContainerBlock`
  and `SimpleContainerBlockEntity`, the generic container classes it and the (already-removed)
  Genome Bank shared, were removed alongside it since nothing else used them.
- Grazing feeds a herbivore but does not yet consume the block, so plant food is effectively
  infinite. Consuming it needs a regional stock to debit, which is Phase B in `MD/ECOLOGY.md`.
- Carcasses are `CreatureEntity` instances and count against the `CREATURE` spawn cap while they
  last. A heavily-hunted area may briefly suppress its own spawning.
- Skinning is CPU-side. Moving it to a GPU vertex shader with a bone palette is the next big
  performance lever if creature counts need to go well past the current budget.

---

## License

[CC BY-NC 4.0](LICENSE)
