# Implementation plan: real navigation on wall surfaces

Task source: `prompt.md`. Branch `port/26.2` (local-only; remote `main` is at the same commit — checkout is current).

## Design decision

Build the surface graph **inside vanilla's A*** — a custom `NodeEvaluator` + `PathNavigation` pair —
rather than a separate pathfinder. Verified against the decompiled 26.2 jar:

- `PathFinder` is reusable as-is: it only talks to `NodeEvaluator.getStart / getTarget / getNeighbors`.
- `Node` identity is position-only (`(x,y,z)` hash). The graph therefore uses **cells, not (cell, face)
  pairs**: a cell is traversable if the creature can stand there *or* cling to a wall beside it. Which
  face it clings to is resolved at follow time from geometry — this collapses inside corners to a free
  facing change and keeps every vanilla assumption about nodes intact.
- `Mob.serverAiStep` ticks `navigation.tick()` every tick (verified line 751), so the navigation itself
  can renew the per-tick `setClimbing` intent — no goal cooperation needed.

**Scope call — walls only, no ceilings.** `climbTravel` has no inverted-attachment mode (`climbFacing`
is a horizontal `Direction`, `faceTheWall` uses `toYRot()`, the renderer tilts 90° about the forward
axis). Emitting ceiling edges the locomotion layer cannot execute would strand creatures; adding a hang
mode means rebuilding movement + rendering the prompt says not to rebuild. The cell-based graph leaves
room to add ceiling nodes later. Everything else in the prompt's want-list (route planning round
overhangs, long-way-round faces, concave caves, floor → wall → floor with mantle/dismount) is covered.

## Verified vanilla facts the code depends on

- `GroundPathNavigation.canUpdatePath()` = `onGround || isInLiquid || isPassenger` — **false mid-climb**;
  must be overridden or paths freeze on the wall.
- `GroundPathNavigation.createPath(BlockPos)` snaps air targets to the surface unless
  `canPathToTargetsBelowSurface` — must set it true or wall targets get moved to the ground.
- `PathNavigation.tick()` drives `MoveControl.setWantedPosition` with a floor-snapped Y — fine for
  ground segments, must be bypassed for climb segments.
- `WalkNodeEvaluator.getStart()` scans **down** for a floor when the mob is airborne — wrong for a
  creature hanging on a wall; needs an override.
- `PathType.OPEN` malus is 0.0, `WALKABLE` 0.0, `BLOCKED` −1.0. Climb nodes will be typed `WALKABLE`
  with an added `costMalus` so ground routes stay preferred and all vanilla type checks behave.
- `PathFinder.neighbors` is `Node[32]`; vanilla emits ≤ 8 per node, the climb edges add ≤ 14 more — safe.

## Files

### [NEW] `src/main/java/dev/jsz/primordia/entity/ai/SurfaceNodeEvaluator.java`

Extends `WalkNodeEvaluator`. All tests block-relative (collision-shape-empty / solid), never measured
from body width — the 0.50 collision floor and juvenile scaling make body-relative tests wrong
(prompt: known bug class). Climb edges only when the mob is a `CreatureEntity` with `canClimb()`.

- `getStart()`: if the creature `isClimbing()`, the start node is its current cell; otherwise super.
- `getNeighbors(...)`: super first (all ground moves, including its let-go/drop edges), then append,
  for current cell `C` (`S` = a solid horizontal neighbour = wall, `A` = a horizontal direction,
  `Y` = up):
  1. **Vertical climb** `C → C±Y`: both cells open, and some wall direction solid beside *both*
     (same-face constraint; the follower re-resolves the face each tick, so a mid-column face switch
     is an inside corner, not a mantle trigger).
  2. **Lateral traverse** `C → C+A` at same height: both open, shared wall direction solid beside both.
     Only emitted when `C` is floorless (a floored cell already has vanilla's walk edges).
  3. **Outside corner** `C → C+S+A`: `C+S` solid, `C+A` open, `C+S+A` open. Matches what
     `turnOutsideCorner` + the sideways press physically do.
  4. **Mantle (top-out)** `C → C+S+Y`: `C+S` solid, `C+Y` open, `C+S+Y` walkable (floor = the wall's
     top block). `climbTravel` auto-mantles when driven up past the wall's end.
  5. **Dismount (ledge entry)** from a floored cell `G`: `G → G+O−Y` when `G+O`, `G+O−Y`, `G+O−2Y`
     all open (a ≥2 drop, so plain step-downs stay vanilla) and `G−Y`, `G−2Y` solid (the rock it will
     hang off — same precondition `ledgeEdge` uses).
- Climb nodes: `type = WALKABLE`, `costMalus += 2.0` (climbing is slower than walking; prefer ground).

### [NEW] `src/main/java/dev/jsz/primordia/entity/ai/SurfaceClimberNavigation.java`

Extends `GroundPathNavigation`.

- `createPathFinder`: install `SurfaceNodeEvaluator`; constructor sets
  `setCanPathToTargetsBelowSurface(true)`.
- `canUpdatePath()`: super `|| isClimbing() || isMantling() || isDescending()`.
- `tick()` override — the handoff logic:
  - **Committed phases** (`isMantling`, `isDescending`): do nothing. `setClimbing` is already a no-op
    during them; the path node is reached by the committed motion and advances on a later tick.
  - **Climb segment** (next node's cell is floorless, or the creature is already climbing): resolve the
    face — a direction solid beside both current and next cell, preferring the current `climbFacing` —
    and drive `climbToward(face, node centre)` each tick. Advance on 3D proximity (~0.6 blocks) instead
    of vanilla's floor-based waypoint test. If standing on a ledge and the next node is the diagonal
    dismount cell: `beginDescent(O)` once, then wait out the committed phase.
  - **Ground segment**: `super.tick()` unchanged (MoveControl path).
  - **Stall guard** for climb segments: no distance-to-node progress for ~40 ticks → `stop()` (vanilla's
    `doStuckDetection` is speed-attribute-based and meaningless mid-climb; skip it there).
- `stop()`: also releases nothing — the climb intent lapses by itself (per-tick renewal), which is the
  safety property the mod already relies on.

### [MODIFY] `src/main/java/dev/jsz/primordia/entity/CreatureEntity.java`

- Override `createNavigation(Level)` → `SurfaceClimberNavigation`. Safe for non-climbers: without
  `canClimb()` no climb edges are emitted and it degenerates to `GroundPathNavigation`. (Navigation is
  built in the `Mob` constructor, before the genome arrives — hence gating per-search in the evaluator,
  not at construction.)

### [MODIFY] `src/main/java/dev/jsz/primordia/entity/goal/CreatureTemptGoal.java`

No structural change. Its existing `navigation.moveTo(player)` now routes over walls when that is the
only way — the overhang/long-way-round cases the prompt lists. The polished direct-steering for food
held overhead stays (it is deliberate interaction design, and better than a path for that case).

### [MODIFY] `src/main/java/dev/jsz/primordia/entity/goal/ClimbWallGoal.java`

Kept as the idle-climb behaviour (it exercises `setClimbing` directly and that remains supported).
Only its wall-approach `moveTo` benefits from the new navigation; no logic changes planned.

### [MODIFY] `gradle.properties`

Bump `mod_version` (required on every rebuild).

## Verification

1. `gradle.bat build` (JDK 25, Gradle 9.6.1 per prompt.md paths) — compiles clean.
2. `gradle.bat runClient`: spawn cave crawlers in a cave (`/primordia spawn`), verify:
   - point-to-point across rock that requires wall → floor → wall routing,
   - a target behind an overhang is reached the long way round instead of pinning,
   - tempt still walks on flat ground and still climbs to overhead food,
   - mantle and dismount are never interrupted mid-way,
   - juveniles and non-climbers path normally.
3. Deploy: copy the new jar to `%APPDATA%\ModrinthApp\profiles\Everything Voxy (1)\mods\`, removing the
   old primordia jar (exactly one at a time).

## Risks / open points

- The outside-corner edge trusts the sideways-press drift to carry the body through the diagonal; if it
  proves flaky in testing, the fallback is to have the follower treat that edge as two half-moves.
- `PathNavigation` timeout logic (`doStuckDetection`'s node-timeout half) still runs for ground
  segments — intended; only the speed-based check is bypassed while climbing.
