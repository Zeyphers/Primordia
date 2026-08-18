# Primordia — Agent Rules

Read [`MD/HANDOFF.md`](../MD/HANDOFF.md) for the current state and
[`MD/PITFALLS.md`](../MD/PITFALLS.md) before touching the geometry pipeline. Several failure
modes here are silent — valid body plan, green tests, wrong creature — and that document is the list
of them.

---

## Build & test

There is **no `gradlew`** in this repo, only `gradle-wrapper.properties`. Gradle and a JDK are
installed separately, and `JAVA_HOME` has to be set on the same command line each time.

```bash
cd "/Users/jake/AI projects __/Primordia"
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ~/dev/tools/gradle-8.10/bin/gradle build
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ~/dev/tools/gradle-8.10/bin/gradle test
```

All tests must pass before anything is considered done. When a test fails, check whether the test or
the code is wrong — in this project it has repeatedly been the test.

## Running the game

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ~/dev/tools/gradle-8.10/bin/gradle runClient
```

> **Superseded rule.** This file used to say *"DO NOT use `gradle runClient`"* and to deploy a jar
> into a Modrinth App profile instead. That was written for the author's Windows machine, where
> `runClient` spawned an invisible window. On macOS it works normally and is how the mod is
> developed and tested here. There is no Primordia profile in the Modrinth App on this machine.

`run/` is gitignored. The dev client has Sodium, Iris, Resourcify, Zoomify and their dependencies in
`run/mods/`, at versions pinned by `loader_version=0.16.5` — see `MD/HANDOFF.md` for the table and why
two of them are held back.

## In-game tooling

`/primordia test` lays out 30 specimens (10 archetypes × 3 sizes) for comparison; `reload` re-rolls
in place, `walk` and `stand` drive the gait, and a seed argument reproduces a set. Posed specimens
render at near tier regardless of distance so the grid is uniform. `/primordia info` reports the
nearest creature including its ornament traits.

## Offline measurement tasks

Three properties of this generator are distributions and cannot be judged one specimen at a time.
Each has a task that measures it without launching the game:

- `gradle diversityReport` — how much variety the generator actually produces.
- `gradle gaitReport` — leg extension, foot contact and body attitude for every archetype over
  blocky terrain. Read **demand** (how far the gait asked a leg to stretch, in multiples of its own
  length; past 1.00 there is no pose that reaches) and **reachmiss** (how far short the toe
  finished). `gradle gaitTrace --args="INSECTOID FLAT 5.0 0"` traces one leg frame by frame when a
  number does not make sense.
- `gradle loopProbe` — how closely the walk repeats over one gait cycle, against how long it has
  been running. The editor's preview plays exactly one cycle on repeat, so this is what says
  whether the seam will show.
- `gradle voxelProbe` — the voxel size each archetype is actually built from, per LOD tier.
- `gradle kneeProbe` — every leg's bend hint: how far each pole sits from its own limb axis, how
  much of it points out to the side, and how far each creature sprawls. Read this when knees look
  inverted or inconsistent between legs.
- `gradle strideProbe` — stride length and the fastest each archetype's legs can carry it,
  against the speed `/primordia test walk` actually drives them at. Read this first when legs
  look too fast: a creature given more speed than its stride covers has no good-looking gait.
- `gradle voiceLab` — the call synthesiser, in a browser.
- `gradle voiceDiversityReport` — how many *distinguishable* voices the synthesiser makes, not how
  wide each parameter's range is. Read **PC1/PC2**: the share of all variation on one or two axes.
  At 68% (where this started) the population is one sound with two knobs — see `MD/PITFALLS.md` §28.

---

## Verifying a change

Geometry bugs here are mostly invisible to reasoning and obvious to measurement. Prefer:

1. **Measure first.** Write a throwaway probe that prints the actual numbers before forming a
   theory. Every real bug this session was found that way and several confident theories were wrong.
2. **Read the artefact, not a reconstruction.** Bake the mesh and inspect its vertices. A test that
   recomputes what the code should have produced cannot catch the code not producing it — this
   caused a false "fixed" report and three rounds of wasted work.
3. **Check the test discriminates.** After a fix, break the code deliberately and confirm the test
   fails. Three tests in this session passed while the bug they existed to catch was live.
4. **Prove the geometry, then say what is unproven.** Tests confirm structure; they do not confirm
   that something looks right. Say which is which.

---

## Animation architecture

All creature animation is procedural — no authored animations exist. The pipeline is:

1. **Gait** — foot plants in world space, step cycle driven by speed
2. **Body** — root transform from foot heights (pitch/roll/bob)
3. **Spine/Neck/Tail** — axial posing with lateral bend, look tracking, tail lag
4. **Jaw** — hinged mandible; the bind pose gapes and rest closes it
5. **Limb IK** — FABRIK solve per leg to world-space foot targets
6. **Arms** — counter-swing with downward posture bias (NOT IK-driven)

Order matters: the axial passes move the hips, so they must run before IK.

Key files:
- `CreatureAnimator.java` — the entire animation system
- `CreatureEntity.java` — entity logic, hitbox dimensions, riding/travel, taming and bonding
- `CreatureRenderer.java` — fills AnimationContext, drives rendering, LOD tier selection
- `BodyPlan.java` / `BodyPlanBuilder.java` — decoded phenotype and the development step

## Hitbox Rules
- Hitboxes encompass **legs and torso only** — NOT tail, neck, or head
- Width is derived from actual leg splay (rest effector X positions), not bodyLength
- Height is `hipHeight * 1.25`, not full bounding box height

## Riding / Head Control
- When a rider controls the creature, `travel()` handles all yaw updates
- `tick()` must skip its bodyYaw easing when `getControllingPassenger() != null` to avoid a feedback
  loop that causes head stutter
- Head yaw is eased toward body yaw, never snapped
- Steering intent reaches the animator as `AnimationContext.riderSteer` and leads the turn; turn
  rate alone reports motion that has already happened, which reads as an unresponsive mount

## Leg Walk Cycle

Everything here is derived from the legs' **reach envelope** — how far each foot can travel before
its hip can no longer hold it — not from body proportions. `CreatureAnimator.buildEnvelope` works it
out once per body plan; `gradle gaitReport` measures whether it is holding.

- **Stride** is twice the tightest leg's half-span, measured about the **middle of that leg's
  fore/aft travel** rather than about the foot's bind position. Body plans grow feet well fore
  or aft of their own hips, so the reach either side of a bind position is lopsided — and
  sizing the stride from the smaller half collapses it to a quarter of hip height. It is *not*
  a proportion of hip height either.
- **Cadence is a tested property**, not a consequence. A gait can be correct on every reach
  metric and still look like vibration; see `MD/PITFALLS.md` §20. `gradle strideProbe` reports
  the fastest each archetype's legs can carry it.
- **Lead** is half the distance the body covers during a stance, computed from the actual step
  frequency. That makes a stride symmetric: the foot lands ahead of the hip, passes under it, and
  leaves behind it.
- **Corrective steps.** A planted foot dragged outside its envelope — by a turn, a block edge, a
  change of speed — steps again, whatever the gait phase says. Feet have their own swing clock
  so an early step is still a properly timed one. Gated two ways, because stance clamps the
  foot *to* the envelope and so parks it on the trigger: a hysteresis margin, and a dwell time
  that a foot genuinely out of reach is allowed to skip.
- **Body height** is the lowest any weight-bearing leg demands, not the mean foot height. This is
  what makes a creature crouch over broken ground, and what keeps limbs inside their reach.
  Rate-limited: it is a minimum over whichever feet bear weight, so it steps every time one
  lands or lifts, and damping alone chases that faithfully enough to judder.
- **Vertical bob** runs at twice the step frequency and fades out as cadence rises. At four
  steps a second an un-faded bob is an eight-hertz buzz — §21.
- **Bend direction.** Every limb records the plane it was grown in (`LimbChain.bindPerp`) as well as
  which side of it each joint sat on (`bendSigns`). The solver rebuilds a bend plane each frame from
  the live hip-to-target axis and must re-anchor it against the bind plane — that axis swings up to
  113° through a stride, and past 90° the stored signs mean the opposite of what they say. §24.
- **Quadrupeds keep the opposed elbow/stifle convention but only at 40% strength
  (`QUAD_FORE_AFT_SHARE`).** At full strength four knees read as aimed at each other across the
  belly; the sign is kept so a digitigrade hock still bends against its own knee.
- **Many-legged creatures radiate.** Knees bend the way their own foot fans, and a leg with no fan
  bends straight out to the side. The quadruped "elbow back, knee forward" rule is not weighted down
  for them, it is dropped — on a middle pair it is undefined and was inverting them. §23.
- **Attitude** is a least-squares plane through the grounded feet, clamped to ±20° and rate-limited.
- Swing arc includes forward overshoot (0.15 * sin(π*s)) for natural anticipatory reach
- A column with no standable surface at all probes 4 adjacent positions; a column *with* an answer
  always gets that answer. Rescuing an out-of-range answer is what put creatures on top of lakes.
- Past what the legs can physically stride, planted feet skate rather than the limbs tearing. Last
  resort, and an ordinary walk never reaches it.

## Genome changes
Genes are appended at the end of the `Gene` enum only, never reordered — ordinals are the wire
format and `Genome.decode` reads by index, defaulting absent loci to 0.5. Adding one changes
`Gene.COUNT` and therefore what `Genome.random` yields for every locus, so expect unrelated
statistical tests to shift.
