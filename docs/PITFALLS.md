# Pitfalls

Failure modes in this pipeline that are **silent** — the body plan stays valid, every existing test
stays green, and the creature is wrong only on screen. Each entry below cost real time to find, and
most were found by measuring rather than by reading code.

The recurring theme: this generator has several places where a mistake is *self-consistent*. The
parts all agree with each other and the whole assembly is wrong. Reasoning cannot catch those,
because the reasoning uses the same wrong frame. Measurement can.

---

## 1. The mesh is baked once and skinned forever

Geometry is polygonised from the SDF in **bind pose** and afterwards only ever skinned. Anything
that must be able to *separate* has to be baked already separated, because skinning cannot create a
seam — it can only stretch what is there.

This is why the mouth is **baked wide open and closed by the animator**, not the reverse. Baked
shut, the mandible and cranium come out of the mesher as one welded lump and no amount of animation
parts them; the jaw bone rotates and the face stretches with it.

`BodyPlan.jawRestAngle` therefore holds the rotation that *closes* the mouth, and rest is a closing
rotation while opening is less of one.

---

## 2. The SDF smooth-union rounds off anything you put in it

The smooth union is what makes a limb grow out of a hip instead of intersecting it. For anything
that wants a hard edge it is exactly wrong.

- **Teeth** were tried as `SdfBlob`s twice. They came out as rounded white lumps welded to the lip.
  They are now `ToothDef` + `ToothMesher`, emitted as geometry outside the field entirely and
  appended to the baked mesh after smoothing, skin binding and winding correction have all run.
- **Armour plates and light organs** had the opposite problem: they were in the *hard*-union set
  (`Feature.isSurfaceDetail`) and read as discs and balls stuck on the body with a visible rim. They
  belong in the smooth union with the cranium and the jaw.

Rule of thumb: keratin and sensory organs want the hard union; flesh and plating want the smooth one.

---

## 3. Anything narrower than one sampling cell does not exist

Surface Nets only emits geometry where the sampled field changes sign. A feature thinner than a cell
falls between samples and is **not coarse — it is absent**, with nothing in the log and a perfectly
valid body plan.

`ThinLimbTest` guards limbs. Teeth hit the same wall at **0.2 of a cell across** and were completely
invisible while every test passed. Measure the ratio before assuming a small feature renders:

```
cellSize ≈ span / resolutionFor(tier)
```

`MeshBaker.resolutionFor` raises resolution until cells are smaller than `plan.minLimbRadius`, so a
new small feature must either be included in that figure or be big enough without it.

---

## 4. Two overlapping solids are one solid

Blend groups stop *nearby* limbs being smoothed together. They do nothing about limbs that
genuinely intersect, and neither does sampling resolution.

Legs were thicker than the gaps between them: an arachnid carried 55 mm-radius legs on hips 33 mm
apart, so **every same-side pair on every creature** interpenetrated by construction and the legs
meshed as one webbed sheet. Hip spacing is now reconciled against leg thickness before the blend
radius is derived.

If something looks fused, measure the surface-to-surface distance between the parts before
suspecting the mesher.

---

## 5. A self-consistent inverted frame

`headDir × headRight` already points up out of the skull. Both places that built the head basis
negated it, so "up" was `(0, -1.00, -0.08)` for a creature facing +Z.

Everything downstream used the same wrong vector, so every *relationship* held — the jaw was
correctly placed relative to the head, the teeth relative to the jaw — and the whole assembly was
upside down. The mandible hinged on top of the braincase, upper teeth grew out through the skull,
and horns grew down out of the chin.

Local-frame tests cannot catch this; they live in the frame that is wrong. `JawTest` now compares
the basis against **world up**, which is the one comparison that cannot be fooled that way.

**When something looks structurally wrong, print the basis vectors before anything else.**

---

## 6. A test that reimplements the code cannot catch the code

This caused more wasted work in this session than every other item combined, and produced a
confident, wrong "fixed, zero clipping, all archetypes" report.

Three separate instances, all the same shape:

1. `ToothClippingTest` computed the expected tooth tip itself. The mesher had a floor under its
   clamp that the test did not model, so the two measured different geometry. The test said 0%; the
   truth was 23%.
2. When teeth began being skipped, the test indexed `plan.teeth` by buffer position. With one tooth
   missing, every later vertex was attributed to its neighbour and half were checked against the
   wrong jaw. `ToothMesher.Result.emitted()` exists to fix this.
3. The survival test compared `min(vertexCount, planned)` against `planned` and so could never
   fail. It hid saurians losing 93% of their teeth.

**Read the artefact, not a reconstruction of it.** Bake the mesh and inspect its vertices.

Corollary: when a change makes no measurable difference, suspect the measurement. Shortening teeth
changed the clipping figure by *exactly zero* twice — at half length and at a fifth — which was the
clue that the number was not measuring teeth at all.

---

## 7. Guards with floors under them are not guards

Twice, a clamp was written as "cap the length, but never below this minimum", and the minimum
branch silently returned an unclamped value in exactly the cases the clamp existed for:

```java
extent = Math.min(desired, ceiling);
extent = Math.max(extent, floor);   // ← defeats the ceiling whenever floor > ceiling
```

If a constraint cannot be satisfied, the honest options are to **drop the feature** or to **return a
sentinel and decide upstream** — not to emit something that violates it. `ToothMesher.NO_ROOM` is
the second option.

---

## 8. Ask the right question of the field

`ToothMesher` marches outward to find where a tooth leaves the gum. Marching the **whole body**
field looks right and is wrong: directly above the back of a mandible is not open mouth, it is
skull, so teeth near the hinge tunnelled up through the head and reported the top of the cranium as
their gum line. Those teeth began outside the opposing jaw before they had any length — which is
why shortening them changed nothing.

Bounded to the bone the tooth grows from, the march answers the question actually being asked.

---

## 9. Solve against the pose the creature actually adopts

Tooth length is solved against the **closed** mouth, because that is the only pose in which a tooth
being too long is observable — in the open bind pose they all sit harmlessly in the gap.

But the animator never fully closes the jaw; ambient breathing holds it slightly ajar. Solving
against a fully shut mouth rejected teeth for colliding in a pose no creature ever holds.
`BodyPlan.JAW_REST_SLACK` and `tightestJawClosure()` exist so the mesher and the animator agree on
what "closed" means.

Both jaws are rigid bodies, so the tip travels a straight line as the tooth lengthens and a
bisection lands on the limit exactly. Three attempts to derive that limit in closed form were all
wrong. Prefer solving to deriving where the geometry is this tangled.

---

## 10. Entities that never move confuse the client

A posed test specimen sends no movement packet, so the client reports it **airborne** and the gait
correctly refuses to run in mid-air — the whole test grid stood frozen. Its head yaw is never driven
either, so it sat at a stale value while the body faced the player, and the animator clamped the
difference to its limit: every specimen craning hard left or right.

Both are overridden for posed specimens in `CreatureRenderer.fillContext`.

Related: IK only runs on the closer tiers (`LodTier.usesInverseKinematics`). A grid sixty blocks
deep had most of its rows in tiers where `solveLeg` never runs, so their legs sat frozen in bind
pose no matter what the walk flag said.

---

## 11. Vanilla forgives what shader packs do not

Vanilla shades entities straight from the interpolated vertex normal. Shader packs branch on
`gl_FrontFacing` and negate the normal for back faces — and creatures draw on a **no-cull** layer,
so a quad wound against its own normals is not culled, it is lit inside-out. One such quad in a
smooth surface reads as a hard facet, and only with shaders on.

`MeshBaker.alignWindingToNormals` fixes what can be fixed, using Newell's method (a normal from
three corners of a **non-planar** quad does not negate cleanly when the order reverses) and choosing
the diagonal that leaves both triangle halves agreeing. Some residual is inherent to emitting quads.

Also on this axis: every vertex sharing one UV gives shader packs a zero-area UV triangle, so
tangent generation falls back to a per-face axis that flips between neighbours. Each quad now gets a
real UV square, kept small because packs read the distance to `mc_midTexCoord` as the sprite's
half-size.

---

## 12. Tests that assert a tendency should say so

`ordinaryQuadrupedsKeepTheirKneesBelowTheHip` originally asserted that *no* grazer ever stands like
a spider. `GRAZER` does not band `LEG_ARCH`, so a grazer rolling a high one is rare, legal, and
exactly the sort of outlier the generator exists to produce. The test was hostage to the random
draw, and adding an unrelated gene — which shifts the whole sequence — broke it.

Adding a gene changes `Gene.COUNT`, which changes what `Genome.random` produces for every locus.
Expect unrelated statistical tests to move.
