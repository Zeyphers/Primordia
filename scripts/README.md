# scripts/

Tooling that is not part of the mod. Node lives here and nowhere else — the mod build never sees
`node_modules`, and nothing in here ships in the jar.

```bash
cd scripts && npm install && npx playwright install chromium
```

## capture.mjs — the gallery turntable

Records the clip on the Modrinth store page: a creature rotating once, re-rolling into a new random
genome every second.

```bash
~/dev/tools/gradle-9.6.1/bin/gradle capture          # one command, starts the editor itself
~/dev/tools/gradle-9.6.1/bin/gradle capture -Pargs="--duration 8 --width 400"

cd scripts && npm run capture -- --seed-start 20260812   # same thing without Gradle
```

Outputs, all from the same PNGs:

| file | what |
| --- | --- |
| `build/capture/frame_%04d.png` | the frames, 2x device scale |
| `build/primordia_editor.gif` | gifski, quality 90, looping forever |
| `build/primordia_editor.mp4` | h264, yuv420p |
| `build/primordia_editor.webp` | animated webp |

If the GIF lands over 8 MB the script re-encodes at width 400, then at 15 fps, and says which
settings won.

### Flags

| flag | default | |
| --- | --- | --- |
| `--duration` | `12` | seconds for one full revolution |
| `--fps` | `20` | |
| `--reroll-interval` | `20` | frames between genome re-rolls |
| `--width` | `480` | output width |
| `--seed-start` | random | first seed; the run is fully reproducible from it |
| `--min-size` | `2.0` | metres of body length; smaller animals are skipped |
| `--walk-speed` | `1.4` | m/s gait; `0` makes it stand and breathe instead |
| `--exposure` | `0.6` | capture-only light scale; `1.0` is exactly what the editor shows |
| `--archetype` | `CHAOS` | any `Archetype` name |
| `--border` | `minecraft` | `none` for a bare viewport |
| `--page-bg` | `#16181c` | Modrinth's dark-mode page background — the frame's outermost ring |
| `--border-px` | `6` | thickness of one bevel ring, in CSS pixels |
| `--viewport` | `720x420` | browser viewport before the 2x scale |
| `--bg` | `#0e1013` | or `transparent` |
| `--margin` | `1.05` | camera pull-back past the swept bounds |
| `--pitch` | `0.22` | camera elevation, radians |
| `--quality` | `90` | gifski quality |
| `--out` | `build/primordia_editor.gif` | the mp4 and webp sit beside it |
| `--max-bytes` | `8388608` | the size the retry ladder is trying to get under |

Every re-roll is printed with its seed, size and vertex count, and rejected seeds are printed too,
so a run can be replayed exactly:

```
reroll @frame 0040  seed 20260819  (2.68m long, 4 legs, 3,201 verts, 32 bones, tier 0)
  skipped 1 under 2m: 20260821(1.22m)
```

### How it works

There is no renderer in here. `capture.mjs` drives the actual editor page — `?capture=1` on the
server `/primordia editor` starts — through Playwright, and the creature on screen comes out of the
same Java bake, the same `CreatureAnimator` clip and the same WebGL shader the editor uses. Capture
mode only strips the chrome, draws the Minecraft GUI frame from the page's own bevel tokens (black
outline, raised two-tone panel, viewport sunk into it, and an outer ring in Modrinth's dark-mode
background so the clip's edge dissolves into the store page), pins the settings (highest LOD tier, no grid, no overlays, no
emissive), and hands the frame clock over: `window.__capture` exposes `setSeed`, `setAngle`,
`setTime`, `waitForBake`, `probe` and `draw`, and the driver steps them by frame index rather than
sleeping on the wall clock.

Two things make the loop seamless. The angle is `2π·i/frames`, a pure function of the frame index,
so a re-roll can never nudge the spin and the last frame is one step short of the first rather than
a copy of it. And every re-roll is awaited to completion — bake, skin upload, walk clip — before the
frame that shows it is taken, so no frame catches a half-baked mesh or a coarser tier.
