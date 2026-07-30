# Task: give Primordia's climbing creatures real navigation on wall surfaces

## The problem in one line

Climbers can move on vertical surfaces, but they are **steered**, not **pathfound**. Give them
navigation on walls that is as capable as the ground pathfinder they already have.

## Context

Primordia is a Fabric mod for **Minecraft 26.2** at `C:\Users\jacob.szczepaniak\dev\Projects\Primordia`,
on branch `port/26.2`. Creatures are procedurally generated: a genome grows a skeleton, the skeleton
grows an SDF body, and the body walks with IK. Nothing about a creature's shape is known until it
spawns, so there are no fixed models, no fixed hitboxes, and no per-species tuning.

One archetype, `CAVE_CRAWLER`, is meant to live on walls. `CreatureEntity.canClimb()` gates the
ability (true for anything subterranean, or with 4+ legs and mass ≤ 0.38).

## What already works (do not rebuild this)

Climbing is **scripted movement the mod owns**, not vanilla's climbable-block behaviour. All of it is
in `CreatureEntity`, under the `// ---- climbing` banner:

- `setClimbing(Direction into, float vertical, float sideways)` — per-tick intent. Must be renewed
  every tick or it lapses, so an interrupted goal releases the wall by doing nothing.
- `travel(Vec3)` hands off to `climbTravel()` whenever a climb, mantle or dismount is running.
  `climbTravel()` sets position, gravity and facing itself. Phases:
  - **approach** — walk the last stride into the wall, still under gravity
  - **climb** — no gravity; press into the face, plus vertical and lateral drive
  - **mantle** — push over the top lip (committed; `setClimbing` is a no-op during it)
  - **dismount** — `beginDescent(Direction over)` backs over a ledge and feels for the face below
  - **corners** — `turnOutsideCorner()` wraps round the end of a wall; blocked lateral movement turns
    onto the blocking face (inside corner)
- Surface queries: `wallBeside(dir)` (block-relative, the "is a climb available" test),
  `touchingWall(dir)` (tight, the "have I arrived" test), `wallAdjacent(preferred)`,
  `ledgeEdge(preferred, minDrop)` / `dropDepth(dir)`.
- `climbToward(into, x, y, z)` — the steering that needs replacing: it projects the offset onto "up the
  face" and "across the face" and drives both.

Consumers: `entity/goal/ClimbWallGoal.java` (idle climbing, both directions, with stall detection and
cooldowns) and `entity/goal/CreatureTemptGoal.java` (goes up or down after food a player holds).

Rendering is done and correct — `client/render/CreatureRenderer.java` tilts the body 90° onto the wall,
offsets it forward by `halfWidth + hipHeight/2` so the feet land on the surface, and while climbing
feeds the animator a flat ground plane at the creature's own feet plus a 3D speed with
`airborne = false`, without which the gait freezes every leg.

## What is actually wrong

`climbToward` is line-of-sight steering. It gets a creature anywhere on the face it is on and round the
corners in between, and then stops being enough:

- a target behind an overhang leaves the creature pressed against the nearest point, going nowhere
- a face reachable only the long way round is never reached
- concave geometry (the inside of a cave, mostly) traps it
- there is no route planning at all, so nothing can reason about "down here, across, then up there"

## What is wanted

Parity with ground movement. Concretely, that probably means:

1. A `PathNavigation` + `NodeEvaluator` that treat vertical faces as traversable nodes, so a path can
   run floor → wall → ceiling → floor. Vanilla's `WallClimberNavigation` is the nearest existing thing
   and is **not** sufficient — it only makes walls passable to a ground path, it does not put nodes on
   surfaces.
2. Path following that hands off cleanly between ground walking and `climbTravel`, including the mantle
   and dismount transitions, which are committed actions no path may interrupt mid-way.
3. Whatever corner and edge cases the node graph needs beyond the two `climbTravel` already handles.

Take your own view on the design. If a full surface graph is the wrong call, say so and argue for the
alternative.

## Things worth knowing before you start

- **Minecraft 26.2 is unobfuscated.** No Yarn, no mappings. Verify any vanilla API by running `javap`
  against `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-81e7221aa5/26.2/minecraft-merged-81e7221aa5-26.2.jar`.
  Do this rather than trusting memory of older versions — a lot has moved.
- **Vanilla's passive climb still exists and is not usable here.** In
  `LivingEntity.handleRelativeFrictionAndCalculateMovement`, after `move()`:
  `if (horizontalCollision && onClimbable()) delta.y = 0.2`, then `multiply(f, 0.8, f)`, then gravity in
  `travelInAir`. It only fires on a tick where the creature happens to have collided, it cannot hold a
  position on a wall, and driving these creatures through it did not work in practice. That is why
  movement is scripted. `CreatureEntity.onClimbable()` still returns true while climbing, for fall
  damage and similar.
- **Collision boxes are floored at 0.50 wide** in `getDefaultDimensions`, while a cave crawler's actual
  body is around a third of a block. Any surface test measured outward from the body's own width fails
  for exactly the creatures that need it — this was a real bug. Prefer block-relative tests.
- **`STEP_HEIGHT` is set to at least 1.0** (`getDefaultDimensions`), so these creatures step over
  one-block obstacles without registering a horizontal collision.
- `Mob.setSpeed(float)` also calls `setZza(speed)` — that is how MoveControl produces forward motion.
- Juveniles exist: `getGrowth()` scales the collision box and the drawn body, so nothing may assume a
  fixed size for a species.

## Build and test

No Gradle wrapper. JDK 25, Gradle 9.6.1:

```
JAVA_HOME=C:\Users\jacob.szczepaniak\dev\tools\jdk-25
C:\Users\jacob.szczepaniak\dev\tools\gradle-9.6.1\bin\gradle.bat build
C:\Users\jacob.szczepaniak\dev\tools\gradle-9.6.1\bin\gradle.bat runClient
```

`run/mods/` already has LambDynamicLights and Mod Menu. Useful in game: `/primordia spawn`,
`/primordia inspect`, `/primordia test`. Spawn cave crawlers in a cave and watch whether they can get
from an arbitrary point to another arbitrary point across the rock.

Bump `mod_version` in `gradle.properties` on every rebuild — jars are deployed to
`%APPDATA%\ModrinthApp\profiles\Everything Voxy (1)\mods\` and **exactly one** primordia jar may be in
that folder at a time.

## Caveat

Nothing in this branch is committed. The whole 26.2 port plus all of the above is uncommitted working
tree, and the branch is shared with another machine via GitHub — fetch before assuming this checkout is
current.
