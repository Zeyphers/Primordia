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
  The answer was `ToothDef` + `ToothMesher`, emitting them outside the field entirely and appending
  them to the baked mesh after smoothing, skin binding and winding correction had all run. *Teeth
  have since been cut from the generator and those classes deleted — the sections below that use
  them as their example are kept for the general lesson, not as a description of current code.*
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

---

## 13. A correct record and a wrong world

`RegionMaterialiser.topUp` walked the region's lineage list spawning as many of each as it could
fit. With an entity budget of ten and a first lineage of forty animals, the first lineage took all
ten slots — every time. A region recorded as holding four species put ten individuals of one species
on the ground, and did it consistently enough that the whole explored map looked like a monoculture.

Everything upstream was right. The ledger held four lineages with sensible populations,
`/primordia region` listed all four, the population round-trip test passed, determinism passed, the
food-chain test passed. Nothing was lost, double-counted, or out of range. The *record* was a
faithful description of an ecology that the world was not rendering.

This is the ledger's version of §5: a self-consistent frame. Every test asked whether the numbers
were right, and they were. None asked whether the numbers reached the player.

Two things worth carrying forward:

- **The tell came from an unrelated system.** `TamingPreference` keys a creature's favourite food
  off its lineage id, so "every animal for a hundred blocks wants sugar cane" was a direct readout of
  lineage identity that nothing was designed to provide. When a symptom seems oddly specific, it is
  often a coincidental probe into state that has no deliberate display.
- **Proportional allocation starves the rare.** The obvious fix — split the budget by population
  share — still gave the smallest lineage nothing, because rounding and a greedy left-to-right pass
  exhausted the budget first. Given the trophic pyramid puts predators at the bottom of the
  population table, the animal that vanishes is always the interesting one. `allocate` now reserves
  one slot per lineage present *before* distributing the remainder, and lives outside the spawning
  code so the test drives the real arithmetic rather than a copy of it (§6).

---

## 14. One-way selection is a ratchet, not a pressure

`RegionSimulation.select` nudged `SIZE` by `-hunger` and nothing else. Satisfaction is almost never a
clean 1.0, so the push was always downward, and a region's pre-history runs a few hundred steps
before a player ever sees it. Every lineage in the world arrived pinned at minimum body size. There
were no large animals anywhere, in any biome, on any seed.

Nothing looked broken. A world of small animals is a perfectly plausible world, every population was
stable, and the trophic pyramid was intact — it was just a pyramid of small things.

Several other loci had the same shape: `METABOLISM` pushed only down, and `SPEED`, `STAMINA`,
`ARMOR`, `FEAR` and `FECUNDITY` pushed only up under any predation at all. All of them would have
railed given enough steps.

**A selection term with no opposing cost is a ratchet.** Over a long enough run it does not produce
an adapted population, it produces a saturated one, and it does it identically everywhere — which
destroys exactly the variety the generator exists to produce. Every trait now has a cost on the
other side: size is bounded by hunger below and predation above, speed and armour cost food to
carry, vigilance costs foraging time.

The test to keep is `selectionDoesNotPinTraitsAtTheirExtremes`, which founds sixty regions and
asserts that every pushed locus still spans a range across the world. It is deliberately a check on
the *spread across regions*, not on any one value: a single lineage sitting at an extreme is a
legitimate adaptation, and a whole world sitting there is a bug.

---

## 15. A fixed proportion is a fixed silhouette

Every creature came out looking like a dog. The head is a cranium blob plus a muzzle blob, and the
muzzle was placed at a fixed two thirds along the skull with a fixed forward extent. `JAW_SIZE`
scaled it and `JAW_WIDTH` made it broader or narrower — a crocodile snout and a weasel snout were
both reachable — but the *proportion* between braincase and face never moved, so the silhouette was
one shape at every point in the gene space.

The missing axis was the one nobody had written down: how far the face carries in front of the
braincase. Flat-faced animals are not animals with small muzzles, they are animals whose muzzle is
broad and set back into the skull, so the fix moves the blob's position and swaps forward extent for
width and depth as it retreats.

Two things this cost time on:

- **`SNOUT_TYPE` was already there and doing nothing.** It gated a beak above 0.68 and its whole
  lower range was dead. A locus that only expresses past a threshold is a locus with unused range.
- **Blending several genes to make a new axis narrows it.** The first attempt averaged three loci,
  which by the central limit theorem concentrates the result around the middle — flat and long faces
  both became rare and almost everything still came out mid-muzzled. A new axis wants one dominant
  locus with light modulation, not a fair blend.

`HeadProfileTest` measures the head's whole fore-aft extent against its width, reading baked blobs
rather than recomputing from the genome (§6), and asserts both ends of the range are actually
reachable — the same thing `OrnamentTest` exists to check, for the same reason: no single head is
invalid, so reachability is all there is to test.

---

## 16. An over-reached limb does not look over-reached

The gait asked legs for targets outside their own reach on **59% of all leg-frames**, averaging 1.39
times the limb's length and peaking at twelve times it. Nothing reported this. Every animation test
passed, `KneeStabilityTest` found no popping, and the solved poses all had a visible bend in them.

The bend is why. `CreatureAnimator.solveLeg` absorbs over-reach by stretching a *working copy* of the
bone lengths and clamping the target to 95% of the stretched chain — so an impossible target still
comes back bent, and every straightness measurement says the limb is fine. What is actually wrong is
that the chain ends up **short**, pointing at somewhere the foot never arrives. On screen that is a
rigid leg held out at an angle with its foot off the ground, which is what "the legs go straight and
pin" describes.

**Measure the gap between the target and the toe, not the shape of the limb.** `GaitRig`'s
`reachmiss` is that number; it ran at 0.507 leg lengths and is now 0.047.

Two roots, both the same shape — a proportion picked by eye standing in for a measurement:

- **Stride was `hipHeight * 1.35`** with a foot planted `0.65` of a stride ahead. Nothing related
  either figure to how far a leg can actually move. Worse, the plant lead was a constant fraction of
  stride while cadence was clamped top and bottom, so wherever cadence and speed stopped agreeing —
  a large animal moving slowly, a small one moving fast — the foot was planted ahead of the hip by
  an unbounded amount and *stayed* ahead for the whole stance. A real stride is symmetric about the
  hip, and the lead that makes it so is half the distance the body covers during a stance.
- **Limbs were grown all but straight.** `LIMB_SLACK` says bones are made 10% longer than the
  hip-to-foot line, and for a C-curve they are; for the digitigrade S-curve the second control point
  crosses the axis and at mid-range lands *on* it, so the two halves of the bow cancel. Bind-pose
  extension ran to 0.99 — an insectoid standing at 99% of its legs' length, with nothing left to
  step with. Whatever a limb's curve shape, the bow is now solved until the arc is long enough.

The reach envelope is the fix and everything else falls out of it: stride length, how far a foot may
be planted, when a dragged foot must step again, and how high the body can ride.

---

## 17. Guessed spans make arctangents lie

Body pitch and roll came from averaging the front feet against the rear, and the left against the
right, over a span guessed as a fraction of the bounding box. The guess had nothing to do with how
far apart the feet in question actually were, so the same one-block height difference produced a
different angle on every creature — and roll was not clamped at all. One foot up a single block on a
narrow animal asked for **sixty-six degrees** of lean and was given it, at four hundred degrees a
second. That is the creature rolling onto its side at a block edge.

A least-squares plane through the grounded feet needs no span, uses the positions that are actually
there, and handles two legs or eight without a special case. Swinging feet are excluded: a foot is
lifted during swing *by design*, and feeding that lift into the body's attitude made every creature
rock in time with its own footfalls.

Related, and the same lesson at a different scale: measuring terrain slope from one sample ahead and
one behind is a coin toss on ground made of blocks. The pair straddles a step, the slope jumps by a
whole block, and the body snaps. Several samples fitted as a line turn the same step into the gentle
grade it visually is.

---

## 18. A scale error inside one frame is invisible from inside that frame

The animator works in unscaled model space; foot plants are world positions; the renderer scales the
whole model by how far grown the creature is. Nothing divided that back out, so a juvenile's drawn
legs reached 42% of the way to ground its feet were correctly planted on.

The test written to catch it — stand a juvenile on a ramp, check its toes land on the ground —
**passed against the bug**. The error scales both axes about the creature's own position, and a
straight ramp through that point maps onto itself under exactly that scaling. Every toe sat
perfectly on a slope that was wrong by more than half.

This is §5 again in a new place: a self-consistent frame cannot be tested from inside itself. The
assertion that works compares the two frames directly — the drawn toe, scaled the way the renderer
scales it, against the world position the foot was planted at.

---

## 19. Redundant systems defeat single-point break testing

§3 says to break the code deliberately and confirm the test fails. Doing that here produced a
surprise worth recording: reverting the stride to its old formula changed nothing, and disabling the
envelope clamp entirely changed nothing either. Both tests still passed.

That is not a bad test, it is a redundant fix — the corrective-step trigger and the body-height
solver each independently keep limbs inside their reach, so removing any one of the three leaves the
other two holding. Good design, and it means **breaking one piece is not a discrimination check when
the pieces overlap.** The check that works is to restore the whole original and run the new suite
against it: six of seven tests failed, each naming the symptom it exists for.

## 20. A stride sized from the tighter half of an asymmetric envelope

Body plans routinely grow a foot well fore or aft of its own hip — a quadruped's front foot commonly
sits two thirds of a leg length ahead of its shoulder. Measured about that bind position the leg's
fore/aft reach is wildly lopsided: 0.14 leg lengths of forward room against 1.38 behind.

Sizing the stride as `2 × min(forward, backward)` throws the larger half away. Worse, the stride is a
minimum across every leg, so one such limb sets the cadence for the whole animal. Measured: stride
collapsed to 0.28 of hip height, step frequency pinned against its ceiling, and every leg blurred.

**None of the reach metrics catch this.** A creature taking paces a tenth of its hip height never
over-reaches, never misses its target and never tips over — it scores *better* on all of them than a
creature that strides properly. The suite was green while the animals shivered. The stride has to be
measured about the middle of each leg's travel, and cadence has to be a tested property in its own
right, because it is the only reading that distinguishes walking from vibrating.

## 21. Frequency, not amplitude, is what reads as "jittering"

The torso's vertical bob is `hipHeight * 0.035` — a third of a percent of a block on a small
creature, and invisible in a still. It runs at *twice* the step frequency, so a creature trotting at
four steps a second bobs at eight hertz, and eight hertz of anything reads as a vibration rather than
a gait.

Two consequences. Any oscillation whose frequency is derived from cadence needs an amplitude that
falls away as cadence rises, or it turns into a buzz precisely when the animal is most visible.
And when measuring smoothness, count direction changes per second, not displacement: the amplitude
here never changed, and the amplitude was never the complaint.

## 22. Clamping to a boundary puts the trigger exactly on the boundary

Stance clamps each planted foot back inside its reach envelope every frame. A corrective step that
fires when the foot is *outside* that envelope therefore fires on a foot sitting exactly on the
threshold, and the next millimetre of body travel re-fires it — a leg stepping every frame, measured
at 13 steps per second per leg against a phase ceiling of 6.

Any test of the form "is this quantity past the limit we just clamped it to" needs hysteresis. It
also needs an escape hatch: a dwell time added to stop the chatter will strand a foot that is
genuinely out of reach, so the gross violation has to bypass the wait that the marginal one respects.

## 23. A convention borrowed from one body plan is undefined on another

Legs were poled by the quadruped rule — elbow bends back, knee bends forward — softened toward a
radial fan for many-legged creatures, but only by 75%. The surviving quarter is harmless on any leg
whose foot fans fore or aft, because the fan term outvotes it.

It is not harmless on the middle pair of a hexapod, whose fan is exactly zero. There the leftover
quarter of a rule about forelimbs and hindlimbs is the *only* term deciding which way the knee
bends. Measured on a flat-legged insectoid: front pair poled +0.92 with its foot fanned forward,
middle pair -0.77 with its foot square out to the side, rear pair -0.92. Neighbouring legs bending
opposite ways for no reason present in the geometry — which is what a report of "some legs look
different from the others" turned out to mean.

When a rule is inherited from a body plan the current creature does not have, the answer is not to
weight it down. It is to drop it: a hexapod has no forelimbs and hindlimbs, just legs.

## 24. A constraint frame rebuilt per frame is not the frame it was recorded in

`LimbChain.bendSigns` records which side of the limb each joint was grown on, measured against the
pole flattened against the **bind** hip-to-foot axis. The solver rebuilt an equivalent vector every
frame from the **live** hip-to-target axis — and as a foot swings through a stride that axis
rotates, carrying the rebuilt vector with it. Measured at up to 113 degrees from the bind plane.

Past ninety the two frames disagree about which way is which, so the re-siding that exists to hold a
knee on its correct side starts driving it to the wrong one. The symptom is a knee that inverts
partway through a stride, on some legs and not others, depending on where each foot happens to be.

Any per-frame reconstruction of a stored convention needs anchoring to the frame the convention was
stored in — here, one dot product and a negate.

## 25. "It will fail loudly" is not a reason to keep a copy

`EditorServer` recomputed the gait cadence rather than asking the animator, and said so:

> Duplication is normally how two copies of a rule drift apart, but this one fails loudly rather
> than silently: get it wrong and the loop visibly jumps, which is the first thing anyone watching
> a walk cycle notices.

The stride stopped being a multiple of hip height and started coming from the legs' reach envelope.
The copy went on dividing by `hipHeight * 1.35`, the clip stopped being one whole gait cycle, and
the preview stopped looping — exactly as predicted, in exactly the described way. Predicting a
failure is not preventing one. If a value cannot be derived outside a class, publish it from inside.

## 26. A loop needs every layer to be periodic, not just the one you are looking at

Even with the clip length correct, the walk preview would not close. The gait was periodic; the idle
layer was not. Breathing runs at 1.7 radians a second, the tail sways at 2.4 and pitches at 1.9, the
jaw idles at 1.5 — all driven from absolute `time` and all deliberately incommensurate with the
stride, because that is what stops a standing animal looking frozen.

The tell was in the error ranking: saurian 0.375, apex 0.19, biped 0.10 — tail size, not leg count.
Anything that has to loop needs those layers switched off rather than tuned, which is what
`AnimationContext.ambient` now does.

## 27. A floor on the thick end of a taper is not a floor

Limb thickness had two minimums, one absolute and one against hip height, and both applied at the
shoulder. The tip is that times the taper, so making the taper a per-creature trait sent the
thinnest limbs straight through a slenderness limit that looked like it was holding — 0.0088 against
a hip height of 0.48, over 50:1.

Clamping the taper instead would have been the wrong repair: holding the shoulder fixed and pulling
the ankle in only ever yields a thinner limb. The floors have to be divided by the taper, so a limb
that narrows harder *starts* thicker. A broad shoulder over a narrow ankle is the silhouette; a
fixed ratio is not a silhouette, it is the absence of one.

## 28. Wide parameter ranges are not variety if they all track one gene

Every voice parameter was derived from the genome over a generous range, and the synthesiser was a
careful source-filter model with four named nonlinearities. It still all sounded the same, and the
reason was not in any one range. Measured over 4400 voices, nine parameters correlated with
`AGGRESSION`: speed quotient 0.95, open quotient -0.92, chaos 0.90, subharmonic 0.90, shimmer 0.90,
jump chance 0.89, jitter 0.82, spectral tilt 0.81, biphonation 0.56. Jitter and shimmer were derived
*from chaos*, so they were a third copy of the same reading rather than separate ones.

Two principal components accounted for **68%** of all variation in the population. Every creature
was somewhere between a big calm one and a small cross one; hearing both corners was hearing the
whole range. Widening any individual range only stretches that sheet — it cannot add a dimension to
it. The fixes were to give roughness its own sources and to add a categorical axis (`VoiceFamily`)
chosen from loci other than temper, taking PC1+PC2 to 56%.

The general form: **the effective dimensionality of a generator is a property nobody notices until
they experience the output in bulk.** It cannot be read off the code, because the code looks like a
lot of independent parameters. Measure it.

## 29. Scores built from different numbers of terms are not comparable

The first version of `VoiceFamily` selection summed weighted trait terms per family and took the
argmax. Families whose scores happened to have more terms, or terms with higher means, simply won:
78% of the population landed in three of the eight families and warble got 0.9%. Eight families in
the code, three in the world.

Nearest-prototype selection has no such bias — every family is a point in the same trait space and
distances are comparable by construction, which spread the same eight families over 5% to 26%
without tuning a single coefficient.

## 30. "Individual variation" from a hash is not heritable

`VoiceProfile` added per-genome variation by scrambling `Genome.hashCode()`, which is
`Arrays.hashCode(values)` — one point mutation changes it completely. So the class's own promise
that "a lineage keeps its voice across generations" held only for the part of the voice derived
from body size; the individual part resampled every birth, and a calf sounded nothing like its
mother.

A weighted sum over every locus gives the same apparent randomness while changing by about a
hundredth per mutation. If a value is meant to be inherited, it has to be a smooth function of the
thing that is inherited — a hash is deliberately the opposite of smooth.

## 31. A target chosen at lift-off is arrived at one swing later

The gait led each foot's plant by half the stance travel — the distance the body covers while that
foot is on the ground — which makes a stride symmetric only if the foot arrives the instant it
leaves. It does not: it arrives one swing later, and at a duty factor of 0.62 the swing carries the
body 0.38 of a cycle while that lead covers 0.31 of one. Every foot therefore touched down *already*
behind its hip and then spent the whole stance falling further back.

Measured across the gait sweep it was **0.29 of a leg length aft, on every leg of every archetype**,
and nothing in the suite caught it: reach, cadence, contact quality and body attitude were all green
the whole time. There is no reading of "is the leg overstretched" or "does the foot reach the
ground" that notices an entire animal being dragged along by its shoulders.

Adding the swing's own travel to the lead brought it to 0.13 overall and 0.06 at a walk, and quietly
improved over-reach, demanded reach and foot-sinking as well. `GaitRig.Result.footBiasMean` measures
it now, and `TerrainGaitTest.feetDoNotTrailBehindTheBody` holds it.

Corollary: whenever a value is computed at one moment and consumed at another, the interval between
them belongs in the arithmetic. The residual at high speed is a different fault — a creature given
more speed than its stride can cover has its plants dragged back by the envelope clamp, which is
honest.

## 32. A power-of-two grid rounds a near miss all the way up

Voxel mode sizes its lattice to a power-of-two multiple of the base voxel so successive LOD tiers
stay aligned. That means a grid **one per cent** coarser than a pixel is not one per cent coarser on
screen — it is rounded to two pixels, and that creature is visibly built from blocks twice the size
of the ones beside it.

The resolution was raised only for thin limbs and narrow gaps, so the genomes that landed there were
the ones asking for nothing: large, thick-legged animals kept the tier's own resolution, and past
about five blocks of span that resolution is coarser than a pixel. It was 0.2% of the population —
rare enough to look like a rendering glitch and impossible to miss when one stands next to a normal
animal, which is the worst combination for an effect whose entire claim is uniformity.

`MeshBaker.resolutionFor` now asks for the world's voxel as well as the creature's limbs. Holding a
6.4-block animal to one pixel cost 2,000 quads → 8,142, well inside the near-tier budget.

## 33. Two rules about the skeleton cannot answer a question about the surface

`SkinBinder` had two guards against a limb dragging geometry it has no business driving: a
parent-chain hop budget, and the blend group that keeps a spider's legs apart. Both reason about the
*skeleton*. The remaining fault was about the *surface* — a frill hangs off a spine bone but sits,
in bind pose, right beside a thigh, so the thigh is the nearest capsule and the frill vertex was
owned by it. Ownership was then allowed to override the group rule, deliberately, because a splayed
foot's outer toe reads as trunk by group and welding it to the spine looked worse.

Measured across eleven archetypes, that escape was driving **26% of every frill vertex the generator
makes from a leg bone, at up to 0.98 weight**, plus 7% of ears and a scatter of plates, horns and
light organs. Invisible standing still; the ornament stretches toward the limb the moment the animal
walks. Every offender was a vertex the field says belongs to the body, so `groupAt` had the answer
all along and nothing was asking it at the right moment.

The rule now is that a leg or arm bone may drive limb surfaces and its own group, and nothing else —
applied to *ownership* as well as to candidacy, since the owner is what anchors the hop budget.
`SkinProbe` (`gradle skinProbe`) prints the split by feature and every ornament row now reads 0.00%.

Two corollaries, both paid for here:

- **Do not treat "has a blend group" as "is a limb".** The jaw carries one, so the first version of
  this rule denied the jaw ownership of its own surface and a jaw vertex picked up spine weight four
  hops away. Leg and arm chain membership is the property actually meant, and `BodyPlan` lists it.
- **Overlapping guards hide each other.** Disabling either half of the fix left the other half
  passing the new test, and it only failed with both off. A test that has not been seen to fail has
  not been shown to test anything — and with two guards that means turning off both.

