# Splicing — why a player would go and study the animals

A design for the payoff the naturalist loop is currently missing. Nothing here is built yet.

---

## 1. The problem, stated precisely

The study pipeline is finished and it is good. Biopsy kit → sample cooler → gene lab → genome report
→ field guide, with `GenomeLibrary` counting how many of a lineage have been through a decoder and
`DecodeAccuracy` walking a species from *Unreferenced* to *Complete* as that count rises. The first
specimen of a species reports `???` at almost every locus; the twelfth reports exact figures. That
progression is real, it is legible, and it took work.

**And the reward for finishing it is a better-worded paragraph.**

That is the whole problem. A player who studies a lineage to *Complete* gets prose where they used
to get hedges. Nothing they can carry, spend, or act with. The advancements (`FULLY_CHARACTERISED`,
`WATCHED_IT_SPLIT`) mark the moment and then it is over. So the loop is self-terminating: you study
one animal to see the system work, and then there is no reason to study a second one, because you
already know what the ending looks like.

Everything below is one answer to that: **make a characterised genome into something you can
become.**

---

## 2. The loop

> Find an animal → study it until you understand it → take what it has → go looking for something
> that has something else.

Concretely:

1. You want to survive a cave dive, so you want to glow.
2. The guide shows you have no lineage on file bright enough to be worth splicing.
3. You go into the caves — where the bioluminescent lineages actually are — and start taking
   samples.
4. Twelve specimens later the lineage reads *Complete*, and its `BIOLUMINESCENCE` shows 0.81.
5. You splice it. You glow. And because you took the whole linkage block, you also took its
   `SUBTERRANEAN` and its pale `SATURATION` — you are now a cave animal, slightly, and the sun is
   less comfortable than it was.

The last step is the design. See §4.

---

## 3. What you splice: a locus you found, at the strength it had

Two rules do almost all of the work.

### Rule 1 — potency is the donor's actual value

Splicing `SPEED` does not give you "the speed buff". It gives you *that lineage's* speed. A
lineage sitting at 0.52 gives you almost nothing; one that has spent forty generations being chased
across open ground sits at 0.88 and gives you a great deal.

This single rule is what converts "go out and look at animals" into progress, because the only way
to know whether a lineage is worth splicing is to **characterise it and compare**. The guide already
stores decoded per-lineage values, so it can rank them. That turns the field guide from an
encyclopedia into a leaderboard, and a leaderboard is a reason to keep collecting.

It also means the interesting donors are *rare and located*. The fastest lineages are in open biomes
under predation; the most armoured are where predation is heaviest; the brightest are underground;
the most heat-tolerant are wherever `RegionClimate` put a hot region. The ecology sim already
produces those gradients. This design does not need to invent a single one of them — it only needs
to give the player a reason to care where they are.

### Rule 2 — you take the linkage block, not the gene

`Mutation.crossover` already cuts the genome at a few points so that linked traits travel together;
that is why offspring look like plausible children. Splicing uses the same unit. **You cannot
cherry-pick a locus. You adopt a contiguous block of the donor's genome and you get all of it.**

The `Gene` enum is already ordered by region, so a block is thematically coherent:

| Block | Contains | What you get, and what comes with it |
|---|---|---|
| Physiology | `DIET`, `METABOLISM`, `SPEED`, `STAMINA` | Speed — and that animal's appetite |
| Disposition | `AGGRESSION`, `SOCIABILITY`, `FEAR`, `CURIOSITY`, `TERRITORIALITY` | Wild creatures read you differently |
| Climate | `TEMP_PREFERENCE`, `HUMIDITY_PREFERENCE`, `ARMOR` | Environmental tolerance, and hide |
| Colour | `HUE`, `SATURATION`, `BRIGHTNESS`, `PATTERN_*`, `COUNTERSHADING` | Cosmetic, cheap, and the tutorial |
| Light | `BIOLUMINESCENCE`, `GLOW_REGION`, `GLOW_HUE` | You emit light, in its colour |
| Habit | `BURROWING`, `NEST_BUILDING`, `GRAZING_IMPACT`, `SUBTERRANEAN` | Dig speed, and a preference for the dark |

So the question is never "which animal is fastest". It is "which animal is fast **and** does not
also make me starve" — and answering that requires several lineages on file, compared. That is the
mechanic that makes the player survey rather than grind one pen.

It is also honest genetics, which is worth something in a mod whose entire premise is that the
biology is real.

---

## 4. Costs, so that it is a choice

A buff with no cost is a levelling system wearing a costume — the same objection `MD/ROADMAP.md`
raised against authored tiers, and it applies here for the same reason. Three costs, layered:

**The package.** Covered above. The drawback is not invented; it is whatever else that particular
animal happens to be. This is the good one, because it is different every playthrough and nobody
designed it.

**Genomic stability.** A per-player pool. Every splice consumes stability in proportion to how far
it moves you from wild type, and the pool grows only with lab tier. So the player is always choosing
between splices, not accumulating them. Reverting a splice returns most of the stability but not all
— scar tissue — so experimentation is affordable and churning is not.

**Uncertainty.** A splice taken from a lineage at *Referenced* rather than *Complete* lands
somewhere inside the report's error bars, and the report already states them: `DecodeAccuracy`
renders `~65%` at `GOOD` and `65%` at `COMPLETE`. Splice early and you get a value drawn from the
range you were shown. **The hedge in the report becomes literal mechanical risk**, which retroactively
makes the whole existing accuracy system mean something. This is the cheapest thing in this document
to implement and possibly the best.

---

## 5. Making the player leave the base

Three pressures, in increasing order of depth.

**Extremes are located.** §3. The strong donors live where the ecology drove them, and the ecology
is already regional.

**Novelty is rewarded.** `Phylogeny` already builds a clade tree and `WATCHED_IT_SPLIT` already
fires when the player has both sides of a fork on file. Extend that: splicing from a lineage the
player *founded knowledge of* — a first-in-world characterisation — costs less stability than
splicing a lineage that was already understood. Being the first to describe something is worth
something, which is exactly true of the real activity being modelled.

**The trait you want may not exist yet.** This is the endgame and it is the one worth building
toward. If nothing in the world is fast enough, you cannot buy it — you have to *make* it. Cull the
slow ones, feed the fast ones, relocate a breeding group somewhere with a predator. The roadmap's
M5 intervention tools become the way you manufacture a donor, and directed evolution over a dozen
generations becomes a thing a player does deliberately rather than a thing that happens near them.

At that point the mod's real subject — that selection produces things nobody wrote down — is
something the player is *using*, not merely watching.

---

## 6. Tiers

Small, visible, early rewards first; the interesting choices later.

1. **Field kit** *(exists)* — scanner, biopsy kit, cooler, gene lab, guide.
2. **Splicing bench** — cosmetic blocks only: colour, pattern, glow. Low stability cost, no
   drawbacks worth the name. This tier exists so the player learns the mechanic on something that
   cannot hurt them, and because glowing in your own creature's colour is a genuinely good reward
   for a first *Complete*.
3. **Retrofit chamber** — physiology, climate, habit. Real stats, real packages, stability starts
   to bind.
4. **Deep splice** — disposition and structural loci. The expensive, strange end: wild creatures
   treating you as kin, or as a rival.

---

## 7. What to build first

A vertical slice that proves the loop end to end, small enough to throw away:

- **One trait**: the Light block. It is visible, harmless, already has a rendering path
  (`DynamicLightsCompat`, `GlowRegion`), and rewards a cave expedition — so it exercises "go
  somewhere specific" on day one.
- **One block**: the retrofit chamber. Consumes a genome report and the lab's existing fuel +
  redstone costs; applies over time like `GeneLabBlockEntity` already does.
- **One data store**: player splices, alongside `PlayerGuideData`, which is already per-player and
  already synced.
- **One UI change**, and it is the important one: a **Self** tab in the field guide listing every
  spliceable block, and for each, the best donor on file with its value — greyed out where the
  player has not characterised anything good enough.

That last item is the whole design in one screen. It is the shopping list, and it is what turns a
guide the player reads into a guide the player *plans from*. If only one thing in this document gets
built, build that, even with nothing behind it — a list of what you could become, mostly empty, is
already a reason to go outside.

---

## 8. Risks and open questions

- **Balance.** Minecraft player buffs trivialise Minecraft. The package rule and the stability pool
  are the intended brakes, but they are untested and this is the most likely thing to go wrong. The
  slice in §7 is deliberately a trait with no combat value, so the loop can be proven before the
  balance problem has to be solved.
- **`SPEED` and friends are ecology genes, not player stats.** They mean something specific to
  `EnergyBudget` and the AI. Mapping them onto player attributes is a translation, and a bad
  translation would make the numbers in the guide lie about what a splice does. The mapping needs to
  be one table in one file, and the guide should quote *that* table.
- **Reversion.** Permanent choices in a survival world with one save are unkind. Reversion should
  exist from the first tier.
- **Multiplayer.** Splices are per-player; `PlayerGuideData` shows the shape. Stability and lab
  tier are per-player too, so nothing here is shared state — but two players splicing from a shared
  `GenomeLibrary` needs a decision about whether one player's study work benefits the other. It
  probably should; the library is already world-scoped and framed as "what this world's science
  knows".
- **Does this cannibalise the ecology?** A player who spends their time breeding donors is not
  watching the world evolve on its own. That is probably fine — it is the same system either way —
  but it is worth watching for, because the mod's best moments are the ones nobody caused.
