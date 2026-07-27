# Primordia — Agent Rules

## Build & Deploy Workflow

Every time you make code changes, follow this exact sequence:

### 1. Set JAVA_HOME and Run Tests
```powershell
$env:JAVA_HOME = 'C:\Users\jacob.szczepaniak\dev\tools\jdk-21'
C:\Users\jacob.szczepaniak\dev\tools\gradle-8.10\bin\gradle.bat test
```
All 47 tests must pass before proceeding.

### 2. Build the Mod Jar
```powershell
$env:JAVA_HOME = 'C:\Users\jacob.szczepaniak\dev\tools\jdk-21'
C:\Users\jacob.szczepaniak\dev\tools\gradle-8.10\bin\gradle.bat build
```
The built jar lands at `build/libs/primordia-0.1.0.jar`.

### 3. Archive the Previous Version
Move the current jar in the Modrinth mods folder to the `old_versions/` subfolder:
```powershell
Move-Item 'C:\Users\jacob.szczepaniak\AppData\Roaming\ModrinthApp\profiles\Primordia\mods\primordia-X.X.X.jar' `
           'C:\Users\jacob.szczepaniak\AppData\Roaming\ModrinthApp\profiles\Primordia\mods\old_versions\primordia-X.X.X.jar' -Force
```

### 4. Deploy with Incremented Version
Copy the built jar to the Modrinth mods folder with an incremented version number:
```powershell
Copy-Item 'c:\Users\jacob.szczepaniak\dev\Projects\Primordia\build\libs\primordia-0.1.0.jar' `
           'C:\Users\jacob.szczepaniak\AppData\Roaming\ModrinthApp\profiles\Primordia\mods\primordia-X.X.Y.jar'
```

**Version numbering**: Increment the patch version by 1 each deployment (0.1.1 → 0.1.2 → 0.1.3 → ...). Check the current version in the mods folder before deploying.

### Key Paths
| What | Path |
|---|---|
| Project root | `c:\Users\jacob.szczepaniak\dev\Projects\Primordia` |
| JDK 21 | `C:\Users\jacob.szczepaniak\dev\tools\jdk-21` |
| Gradle | `C:\Users\jacob.szczepaniak\dev\tools\gradle-8.10\bin\gradle.bat` |
| Built jar | `build/libs/primordia-0.1.0.jar` |
| Deploy target | `C:\Users\jacob.szczepaniak\AppData\Roaming\ModrinthApp\profiles\Primordia\mods\` |
| Old versions | `C:\Users\jacob.szczepaniak\AppData\Roaming\ModrinthApp\profiles\Primordia\mods\old_versions\` |

## DO NOT use `gradle runClient`
The user launches Minecraft through the **Modrinth App**, not `gradle runClient`. The `runClient` task spawns an invisible window that cannot be brought to the foreground. Always build, deploy to Modrinth, and tell the user to launch from the Modrinth App.

## PowerShell Quirks
- Always set `$env:JAVA_HOME` in the same command line as gradle — PowerShell `set` does not persist across separate commands.
- Do NOT use `.bat` files for launching — PowerShell parses `@echo off` as a splatting operator and fails.

## Animation Architecture
All creature animation is procedural — no authored animations exist. The pipeline is:
1. **Gait** — foot plants in world space, step cycle driven by speed
2. **Body** — root transform from foot heights (pitch/roll/bob)
3. **Spine/Neck/Tail** — axial posing with lateral bend, look tracking, tail lag
4. **Limb IK** — FABRIK solve per leg to world-space foot targets
5. **Arms** — counter-swing with downward posture bias (NOT IK-driven)

Key files:
- `CreatureAnimator.java` — the entire animation system
- `CreatureEntity.java` — entity logic, hitbox dimensions, riding/travel
- `CreatureRenderer.java` — fills AnimationContext, drives rendering
- `BodyPlan.java` — decoded phenotype (skeleton, SDF, legs, arms, palette)

## Hitbox Rules
- Hitboxes encompass **legs and torso only** — NOT tail, neck, or head
- Width is derived from actual leg splay (rest effector X positions), not bodyLength
- Height is `hipHeight * 1.25`, not full bounding box height

## Riding / Head Control
- When a rider controls the creature, `travel()` handles all yaw updates
- `tick()` must skip its bodyYaw easing when `getControllingPassenger() != null` to avoid a feedback loop that causes head stutter
- Head yaw is eased toward body yaw, never snapped

## Leg Walk Cycle
- Feet reach *ahead* during swing phase (lead = stride * 0.65), not just halfway
- Swing arc includes forward overshoot (0.15 * sin(π*s)) for natural anticipatory reach
- Foot plants over holes/pits probe 4 adjacent positions for solid ground
