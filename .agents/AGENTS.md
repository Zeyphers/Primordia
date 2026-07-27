# Primordia — Agent Rules

Read [`HANDOFF.md`](../HANDOFF.md) for the current state and
[`docs/PITFALLS.md`](../docs/PITFALLS.md) before touching the geometry pipeline. Several failure
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
`run/mods/`, at versions pinned by `loader_version=0.16.5` — see `HANDOFF.md` for the table and why
two of them are held back.

## In-game tooling

`/primordia test` lays out 30 specimens (10 archetypes × 3 sizes) for comparison; `reload` re-rolls
in place, `walk` and `stand` drive the gait, and a seed argument reproduces a set. Posed specimens
render at near tier regardless of distance so the grid is uniform. `/primordia info` reports the
nearest creature including its ornament traits.

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
- `ToothMesher.java` — the one piece of geometry that bypasses the SDF entirely

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
- Feet reach *ahead* during swing phase (lead = stride * 0.65), not just halfway
- Swing arc includes forward overshoot (0.15 * sin(π*s)) for natural anticipatory reach
- Foot plants over holes/pits probe 4 adjacent positions for solid ground

## Genome changes
Genes are appended at the end of the `Gene` enum only, never reordered — ordinals are the wire
format and `Genome.decode` reads by index, defaulting absent loci to 0.5. Adding one changes
`Gene.COUNT` and therefore what `Genome.random` yields for every locus, so expect unrelated
statistical tests to shift.
