# System architecture notes

Append-only save-state for large changes; read before planning the next one.

## Wall-surface navigation (0.2.0-26.2-alpha.18)

Climbers pathfind over walls through vanilla's own A*, not a parallel pathfinder.

- **Graph**: `entity/ai/SurfaceNodeEvaluator` (extends `WalkNodeEvaluator`). Nodes are plain cells —
  vanilla `Node` identity is position-only, so no face is stored; a cell is in the graph if the
  creature can stand there or cling beside it. Climb edges (gated on `canClimb()`): vertical along a
  shared wall, lateral across a face, outside corner (`C → C+S+A`), mantle (`C → C+S+Y`), dismount
  (`G → G+O−Y`, needs 2-deep drop + rock under the lip). Climb cells are typed `WALKABLE` with
  `costMalus` 2.0 so ground routes stay preferred. No ceiling edges — `climbTravel` cannot execute
  them. `getStart()` uses the current cell when on a wall (vanilla scans down for a floor).
- **Follower**: `entity/ai/SurfaceClimberNavigation` (extends `GroundPathNavigation`), installed via
  `CreatureEntity.createNavigation` for every creature (nav is built before the genome arrives; the
  evaluator gates per-search). Ground segments are pure vanilla. Climb segments bypass MoveControl
  and renew `climbToward` per tick — same lapse-if-not-renewed contract as the goals, so a dead
  navigation drops the creature safely. Face is re-derived from blocks each tick (single-block grip
  test, looser than `wallBeside`'s 2-high start test). Mantle/dismount are committed: navigation
  does nothing until they finish. Own nearest-approach stall check (~40 ticks); vanilla's is
  speed-attribute-based and wrong mid-climb. Overrides that matter: `canUpdatePath` (vanilla's is
  false off-ground → paths froze mid-wall), `setCanPathToTargetsBelowSurface(true)` (air targets
  otherwise snapped to the surface below).
- **Consequence for goals**: anything calling `navigation.moveTo` gets wall routing for free.
  `CreatureTemptGoal`'s direct overhead-food steering and `ClimbWallGoal`'s idle climbing kept —
  they drive `setClimbing` directly, which remains supported.
- **canClimb** gate: subterranean archetype, OR ≥4 legs with mass ≤ 0.38, OR ≥6 legs with mass ≤ 0.6.
