![Primordia](https://cdn.modrinth.com/data/cached_images/5b3d6c167245848de481471729e64fb9cb9a7e2f.png)

<p align="center">
  <img src="https://cdn.modrinth.com/data/cached_images/73bbccd5af9c5ad6aa465922fd8cb4d38321b5ed.gif" alt="Creatures generated live in the creature editor" width="480">
</p>

---

**Every creature in this mod is grown from a genome at runtime. There are no models, no textures,
and no animation files.** A gene vector becomes a skeleton, the skeleton becomes a signed distance
field, the field becomes a mesh, and the mesh walks on legs solved with inverse kinematics. Its
voice is synthesised from the same anatomy. Then they breed, mutate, diverge into lineages and
reshape the world they live in, and once you have studied one closely enough, you can splice part
of it into yourself.

You will not meet the same animal twice.

---

## Install

**You need:**

| | |
|---|---|
| Minecraft | **26.2** |
| Mod loader | **Fabric Loader 0.19.3** or newer |
| Fabric API | **0.155.2+26.2** or newer |
| Java | **25+** (any modern Minecraft launcher installs this for you) |

**Steps:**

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 26.2.
2. Download **Fabric API** ([Modrinth](https://modrinth.com/mod/fabric-api) ·
   [CurseForge](https://www.curseforge.com/minecraft/mc-mods/fabric-api)) — Primordia will not
   load without it.
3. Download the latest `primordia-<version>-26.2.jar` from the [Releases page](../../releases/latest).
4. Put **both** jars into your `mods` folder:
   - Windows — `%APPDATA%\.minecraft\mods`
   - macOS — `~/Library/Application Support/minecraft/mods`
   - Linux — `~/.minecraft/mods`
   - Using the Modrinth App or Prism? Open your instance's own mods folder instead.
5. Launch the game with the Fabric profile.

On a server, install it on both the server and every client. Creatures generate in the world on
their own; no configuration is needed.

### Something went wrong?

| Symptom | Cause |
|---|---|
| Game crashes on startup | Fabric API missing, or the wrong Minecraft version. Primordia is 26.2 only. |
| Creatures are bright magenta | A texture failed to load — report it, this is a bug. |
| Frame rate drops with many creatures | Lower the quality preset (see **Settings** below). Start with `Low`. |
| No creatures anywhere | They spawn like normal animals — give it time, and try a grassy biome. Or use `/primordia spawn` with cheats on. |

---

## Getting started

Creatures spawn naturally, but with cheats enabled you can skip the waiting:

| Command | Effect |
|---|---|
| `/primordia spawn` | One random creature |
| `/primordia spawn 10` | Ten of them |
| `/primordia info` | Full breakdown of the nearest creature — genome, body, ecology |
| `/primordia test` | A grid of test creatures, side by side, for comparison |

New players are given a **Field Guide** on their first join. Every creature you study is filed in
it by lineage, and its family tree shows parents, offspring, and the moment a lineage diverges far
enough to count as something new. Try `/primordia breed` on two creatures, then on their offspring,
and keep going until you see `(NEW LINEAGE)`.

The **creature editor** runs in your browser. `/primordia editor` opens it, no cheats needed: drag
any of the 88 genes and watch the body rebuild, with a description of what each gene does on hover.

---

## Studying creatures

In survival, knowledge comes from specimens.

1. **Take a sample.** Use a **Biopsy Kit** on a living creature. A kit is good for five specimens and
   refuses an individual you have already sampled.
2. **Keep it cold.** Tissue degrades over time, and a degraded sample still reads, only less
   precisely. A **Sample Cooler** slows that to a tenth of the normal rate, and keeps its contents
   when you pick it up.
3. **Sequence it.** Load the sample into a **Basic Gene Lab**. Reading the tissue burns furnace fuel;
   interpreting the read draws redstone. The result is a **Genome Report**, filed into your guide.
4. **Study more of the same species.** A species' first report is mostly `???`. Every further
   specimen of that lineage sharpens its reports, from *Unreferenced* up to *Complete*.

Recipes unlock as you progress, so the recipe book fills in as you go.

---

## Splicing

What you study, you can take. Craft a **Splicing Bench** from a Gene Lab, a diamond, iron, glass and
redstone, choose a lineage from your guide, and the bench isolates one block of that animal's genome
into a **Trait Serum**. Isolating costs a heart; drinking the serum applies the splice.

| Branch | What it carries |
|---|---|
| Physiology | Speed and stamina, and the donor's appetite with them |
| Disposition | How wild creatures read you |
| Climate | Tolerance for where the donor lived, and the hide it needed there |
| Colour | Its colouring and markings; costs nothing but a slot |
| Light | A glow in its colour, from the parts it lit |
| Habit | Its digging and nesting |

- **Strength is the donor's own value.** A lineage that has outrun predators for generations gives
  real speed; a sedentary one gives almost nothing. Each branch in the guide names the best donor you
  have on file.
- **You take the whole block.** Splicing adopts a contiguous stretch of the genome, so you get
  everything in it, costs included. The bench shows all of it before you commit.
- **Depth is earned by finding strong examples.** Each branch opens at *Trace* and deepens to
  *Expressed* and *Dominant* as you characterise more lineages that carry the trait strongly.
- **Slots force a choice.** Two to start, one more for every branch taken to Dominant, up to five.
  Reverting is free, but only at the bench.

The guide's **Self** tab shows your own model with a card for each branch and the part of the body
it changes. The reasoning behind every rule is in [`MD/SPLICING.md`](MD/SPLICING.md).

---

## Works with

All optional; Primordia runs without any of them.

- **[Mod Menu](https://modrinth.com/mod/modmenu)** — every setting below, editable in game.
- **[LambDynamicLights](https://modrinth.com/mod/lambdynamiclights)** — bioluminescent creatures
  light the caves they live in, and so do players carrying a Light splice.
- **[Controlify](https://modrinth.com/mod/controlify)** — controller bindings, and a Field Guide you
  can page, pan and navigate with a gamepad.

---

## What's new in 2.15

- **Splicing**, above.
- **Legs no longer pass through each other.** Knees now point away from the middle of the body, and
  a stride can no longer carry one foot into the next. Same-side legs overlapping mid-walk fell from
  60% of a saurian's frames to 5%.
- **Creatures keep the colour of their biome** instead of drifting to random hues over a region's
  history.
- **Frills and dorsal spines mesh solid**, four-legged creatures no longer grow a third leg segment
  that popped in and out, and about one creature in five is nocturnal rather than half of them.
- **Quieter herds.** Idle calls and snoring are a third as frequent, and big animals no longer drown
  out the rest.
- **Controller support** through Controlify, and **block icons** that match the placed blocks.

Earlier: 2.5 gave voices structurally different families and sized the gait from each leg's real
reach; 2.0 replaced sampled sounds with synthesised voices and widened creature variety. Full notes
are on the [Releases page](../../releases).

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
| `/primordia spawn code <genome>` | One exact genome, pasted from the editor's **Genome code** box |
| `/primordia test` | A grid of test creatures for visual comparison |
| `/primordia test reload` | Rebuild that grid where it stands |
| `/primordia test walk` / `stand` | Toggle whether the test grid animates |
| `/primordia info` | Full breakdown of the nearest creature's genome, body and ecological state |
| `/primordia collect 48` | File every creature within radius into your field guide |
| `/primordia region` | The ledger for the region you are standing in: populations, trends, vegetation |
| `/primordia breed` | Cross the two nearest creatures; reports genetic divergence |
| `/primordia mutate` | Spawn a mutated clone of the nearest creature |
| `/primordia clear 32` | Remove creatures within 32 blocks |
| `/primordia stats` | Mesh cache and bake queue depth |
| `/primordia editor` | Opens the creature editor — **the only one that does not need cheats** |

Everything except `editor` needs gamemaster permissions (cheats on, or op). The editor is a
modelling tool that cannot read or write the world, so it is open to anyone with the mod installed;
it binds to `127.0.0.1`, so on a dedicated server the page is only reachable from the machine
hosting it.

Testing tools live under `/primordia debug` and exist only when the game is launched with
`-Dprimordia.debug=true`: `decay [ticks]` winds nearby carcasses through their stages of rot,
`skeleton [count]` spawns remains directly, and `lava [radius]` walks every creature in range into
the nearest exposed lava.

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
`build/libs/primordia-<version>.jar` and a matching sources jar. `gradle runClient` launches a dev
client with the debug commands switched on.

**Development tools**, none of which need the game running. Each snapshots its classes at launch,
so restart after a code change.

| Task | What it does |
|---|---|
| `gradle editor` | Creature editor on `http://127.0.0.1:8090/` — tweak a genome, see the body |
| `gradle voiceLab` | Voice synthesiser on `http://127.0.0.1:8091/` — tweak a voice, hear it |
| `gradle capture` | Records the store-page turntable clip from the editor (needs Node; see [`scripts/README.md`](scripts/README.md)) |
| `gradle diversityReport` / `voiceDiversityReport` | How much variety the body and voice generators actually produce |
| `gradle gaitReport` | Walks every archetype over generated terrain: reach, foot contact, cadence, body attitude |
| `gradle gaitTrace` | One leg of one specimen, frame by frame |
| `gradle kneeProbe` / `kneeSideProbe` | Knee bend hints in the bind pose / knee direction and leg collisions over a walk |
| `gradle loopProbe` | Whether the walk repeats over one gait cycle; `--args=sweep` checks ten specimens per archetype |
| `gradle skinProbe` / `strideProbe` / `voxelProbe` | Which bones drive which surfaces / fastest walk per archetype / voxel size per archetype |

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

**Voices synthesised, not sampled.** Each call is generated from the creature's anatomy through a
source-filter model: pitch from body mass, formants from head and neck length, roughness from
temperament. Creatures are sorted into voice families by mechanism, so a whistle is not simply a
quiet roar, and the synthesised samples are handed straight to the vanilla sound engine, so 3D
attenuation and the volume sliders all apply.

**FABRIK, not analytic IK.** Limbs have two *or* three segments depending on the genome. FABRIK
handles both with one implementation and no trigonometry. Its one weakness is that it has no
opinion about which way a joint bends, so every limb records which side of its hip-to-foot line
each joint was grown on, and the solver holds the chain in that plane and on those sides every
frame. That is also what lets a digitigrade leg keep its knee and hock bending in opposite
directions.

**Knees point away from the middle of the body.** Front knees bend forward, hind knees back, and
a biped's knees forward. Following the skeleton instead (elbow back, stifle forward) is
anatomically true of joints hidden inside a real animal's body wall and wrong for the joints you
can see, and it aimed a quadruped's knees at each other until its lower legs crossed mid-stride.

**World-space foot plants.** A planted foot is stored in absolute world coordinates, so the body
moves over a foot that genuinely does not move. This is the whole difference between a walk cycle
and a skating animation.

**Stride is sized from reach, then from the neighbours.** Each leg's reach envelope says how far
its foot may travel before the hip can no longer hold it, and the stride is the largest every leg
can manage. On one or two pairs of legs it is capped again by the gap to the next foot on the same
side, charged only for the approach that pair's gait phase produces, so a foot never swings into
its neighbour.

**Feet ease into plants; they are never assigned.** Stance and stop converge exponentially onto
the plant, and IK targets are clamped into the leg's reach so the knee never locks straight and
then snaps back.

**The ground probe rejects surfaces you could not step onto.** A candidate surface must have clear
headroom above it and be within step height of the creature's own feet, so a foot never latches
onto the side of a wall or tree trunk as the creature walks past.

**Slopes bend the spine, not just the root.** Rotating the whole creature rigidly to match the
terrain reads as a plank tilting. 55% of the pitch is applied at the root and the rest is
distributed along the spine, weighted toward the middle of the back.

**Legs are fitted to the ground, not the other way round.** `BodyPlanBuilder` picks a hip height,
pins the foot to y = 0, and derives bone lengths from the curve between them. A creature therefore
*cannot* generate with legs too short to stand on.

**Blockbench models are read as they are.** The Splicing Bench is a `.bbmodel` loaded straight out
of the resources and drawn by a block entity renderer, animated only while it has work. Its
inventory icon goes through the same renderer as a special item model, so the file the artist edits
is exactly what the game draws in the world and in your hand.

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
disappears from the mesh entirely. The lift is capped at 1.8× the tier value and at
`MAX_RESOLUTION`, so one slender genome cannot demand a grid that takes seconds to bake.

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

The suite fuzzes hundreds of random genomes against the invariants that have no visual tell. A
few of the load-bearing ones:

- **`BodyPlanTest`**, **`SkeletonTest`**: every genome yields a valid, deterministic skeleton, and
  a zero pose produces exactly identity skinning matrices.
- **`FabrikTest`**, **`KneeStabilityTest`**: the solver reaches reachable targets without
  stretching bones, knees keep the side they were grown on through a stride, every knee points away
  from the middle of the body, and four-legged creatures' legs do not cross mid-walk.
- **`LimbSeparationTest`**: separate limbs never fuse into webbing or physically intersect, and
  every limb still joins the body.
- **`MeshBakeTest`**, **`QuadWindingTest`**: meshes are consistent, skin weights sum to 1, bind
  pose reproduces the bake exactly, and quads face the way their normals point (shader packs
  render mis-wound quads inside-out).
- **`JawTest`**, **`OrnamentTest`**: the jaw is a hinged bone that swings *down*, and every horn,
  tail and glow region is reachable from some genome and meshes.
- **`EditorClipLoopTest`**: the walk repeats over exactly one gait cycle, which is what lets the
  editor's preview loop without a seam.
- **`GenomeTest`**: serialisation round-trips, malformed codes degrade to null, mutation never
  escapes [0,1], and offspring are closer to their parents than to strangers.
- **`SpliceTreeTest`**, **`SplicerModelTest`**: every stat-carrying splice branch can cost the
  player something, and the bench model ships, loads and loops its sampling cycle.

---

## Known rough edges

- Six- and eight-legged creatures' neighbouring legs still touch at the knees while walking.
  `gradle kneeSideProbe` measures it.
- `CreatureRenderer` bundles its own `assets/primordia/textures/misc/white.png` rather than
  depending on vanilla's, after that asset moved during the 26.2 port; if a future version relocates
  or removes it again, creatures render magenta until `TEXTURE` is repointed.
- Grazing feeds a herbivore but does not yet consume the block, so plant food is effectively
  infinite. Consuming it needs a regional stock to debit, which is Phase B in `MD/ECOLOGY.md`.
- Carcasses are `CreatureEntity` instances and count against the `CREATURE` spawn cap while they
  last. A heavily-hunted area may briefly suppress its own spawning.
- Skinning is CPU-side. Moving it to a GPU vertex shader with a bone palette is the next big
  performance lever if creature counts need to go well past the current budget.

---

## License

[CC BY-NC 4.0](LICENSE)
