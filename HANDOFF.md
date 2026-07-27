# Handoff

Start here, then read [`README.md`](README.md) for what the mod is and
[`docs/PITFALLS.md`](docs/PITFALLS.md) before changing anything in the geometry pipeline.

**State:** builds clean against Minecraft 1.21.1, 76 tests pass, `main` is pushed and in sync.

---

## This machine

The repo ships `gradle/wrapper/gradle-wrapper.properties` but **no `gradlew` script**, so there is
no wrapper to invoke. Gradle and a JDK were installed separately:

| | |
|---|---|
| JDK 21 | `/opt/homebrew/opt/openjdk@21` (via `brew install openjdk@21`; only JDK 19 was present) |
| Gradle 8.10 | `~/dev/tools/gradle-8.10/bin/gradle` (matches the wrapper properties) |

Every command below needs `JAVA_HOME` set inline — it does not persist.

```bash
cd "/Users/jake/AI projects __/Primordia"
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ~/dev/tools/gradle-8.10/bin/gradle build
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ~/dev/tools/gradle-8.10/bin/gradle test
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ~/dev/tools/gradle-8.10/bin/gradle runClient
```

`.agents/AGENTS.md` says never to use `runClient`. **That rule is about the author's Windows box**,
where it opened an invisible window. On macOS it is the normal way to run, and it is how everything
in this session was tested. There is no Primordia profile in the Modrinth App here.

### Dev client mods

`run/` is gitignored, so these are not in the repo and a fresh checkout will not have them. They
were fetched from Modrinth into `run/mods/`:

| mod | version | why this version |
|---|---|---|
| Sodium | 0.6.9 | the build Iris 1.8.7 pins |
| Iris | 1.8.7 | pins a known-good Sodium |
| Resourcify | 1.8.5 | |
| Zoomify | 2.14.4 | newest needs Fabric Loader ≥0.18.0; the project pins 0.16.5 |
| fabric-language-kotlin | 1.12.3 | 1.13.x needs Loader ≥0.16.9; the project pins 0.16.5 |
| YetAnotherConfigLib | 3.7.1 | Zoomify dependency |

Two of those are pinned *down* by `loader_version=0.16.5` in `gradle.properties`. Bumping the loader
would allow current versions of both. `fabric_version` was raised 0.102.0 → 0.116.14 (same 1.21.1
line) because YACL requires ≥0.104.0.

Shaderpacks in `run/shaderpacks/`: BSL 10.1.3, Complementary Reimagined and Unbound r5.8.1. Note
Complementary r5.8.1 is written against a newer Minecraft than 1.21.1 — Iris logs unresolved
uniforms (`inPaleGarden`, `endFlashFactor`) on load. Not fatal; BSL is the cleaner match for testing.

---

## Commands worth knowing

```
/primordia test            30 specimens: 10 archetypes x 3 sizes, named, facing you
/primordia test reload     re-roll in place (keeps the anchor, so sets are comparable)
/primordia test <seed>     reproduce an exact grid
/primordia test walk       drive the gait   ·   /primordia test stand
/primordia info            nearest creature, including the ornament line
```

Posed specimens render at **near tier unconditionally**, bypassing both the distance tier and the
crowd budget. Deliberate: a grid whose far rows are coarser cannot be used to judge a change,
because half the difference on screen would be LOD rather than the generator.

Settings are in **Mods → Primordia** (Mod Menu) or a keybind under Controls → Primordia (unbound by
default). Quality presets run Potato → Ultra; Ultra is sized for the whole test grid at near detail.

---

## What changed here

Eight commits on top of `3482b50 Initial commit`.

**Anatomy.** Six eye styles, seven horn types, five tail shapes, four ear types, frills, beaks,
tusks, bioluminescence. Two-part arachnid bodies (clustered legs, abdomen, knees above the hip) and
`ARACHNID` / `CRUSTACEAN` archetypes. A hinged mandible with teeth.

**Legs no longer web together.** They were physically intersecting — thicker than the gaps between
them — so the union of two overlapping solids was one solid. Hip spacing is reconciled against leg
thickness, feet fan fore-and-aft, and the pole vector goes radial past two pairs. Arachnid
same-side leg pairs intersecting: 360/360 → 12/360.

**Companions.** Tamed creatures can bond (12% on taming, 8% per feed) into wolf-like followers that
defend their owner and can be told to stay. `CreatureEntity` extends `PathAwareEntity`, not
`TameableEntity`, so the three goals are hand-written.

**Spawning.** Superflat and debug worlds generate nothing. Passive weight 80 → 10 and the duplicate
`MONSTER` registration removed — it was spawning the same entity against two independent mob caps
with hostile top-up behaviour.

**Client.** Quality settings screen with LOD presets, per-tier budgets and ranges, mesh cache size
and a normal-smoothing slider. Bioluminescence emits on `entity_translucent_emissive`.

---

## Open threads

**The shader faceting is not confirmed fixed.** A real bug was found and fixed — roughly 1 quad in
200 was wound against the normals it carried, which vanilla ignores and shader packs render
inside-out via `gl_FrontFacing` — taking the worst case from ~0.5% to under 0.25%. Whether that is
what the user was seeing was never established. **No screenshot was ever obtained**, despite a
watcher on `run/screenshots/` for most of the session. Getting one is the single highest-value next
step; the smooth-normals slider is a fast discriminator, because if dragging it changes nothing the
cause is not normals at all.

**Saurians drop 18% of their teeth** as genuinely unfittable — the mouth has nowhere to put them.
Everything else keeps 100%. If the gaps look wrong the fix is saurian jaw proportions, not the teeth.

**Nothing about the mouth has been verified by eye.** The jaw, the mandible silhouette and the teeth
are all confirmed geometrically and by test only.

**The quad winding residual is inherent.** A quad bent over a knuckle has two triangle halves that
disagree with each other, and no winding or diagonal choice reconciles that. Reaching zero means
emitting triangles and a custom render layer.

---

## Where the invariants live

Fifteen test classes. The ones that encode something non-obvious:

| test | what it pins |
|---|---|
| `LimbSeparationTest` | limbs do not intersect, and legs get their own blend groups |
| `ThinLimbTest` | nothing is finer than the mesher can resolve; near-tier stays inside budget |
| `JawTest` | the skull is the right way up, the jaw hangs below it and opens downward |
| `ToothClippingTest` | no tooth comes through the opposing jaw with the mouth shut |
| `QuadWindingTest` | quads face the way their shading normals point |
| `PoseWalkTest` | a stationary creature fed a walking speed actually moves its feet |
| `OrnamentTest` | every horn type, tail shape and glow region is reachable |

Read `docs/PITFALLS.md` before adding to these. Several of them were written wrong first, passed,
and hid the bug they existed to catch.
