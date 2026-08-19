# Splicing — why a player would go and study the animals

A design for the payoff the naturalist loop was missing.

**Status: built, less the splicer block.** The tree, the six branches, the depth rule, slots, the
effects table, the loadout and the Self tab are all in `dev.jsz.primordia.splice` and covered by
`SpliceTreeTest`. Item 3 of the build order below — the splicer block — is not: splicing happens at
the existing gene lab instead, which preserves the rule §6 actually cares about (a bench, so you
cannot re-spec halfway down a cave) without a second machine. Two notes from building it are in §11.

---

## 1. The problem, stated precisely

The study pipeline is finished and it is good. Biopsy kit → sample cooler → gene lab → genome report
→ field guide, with `GenomeLibrary` counting how many of a lineage have been through a decoder and
`DecodeAccuracy` walking a species from *Unreferenced* to *Complete* as that count rises. The first
specimen of a species reports `???` at almost every locus; the twelfth reports exact figures. That
progression is real, it is legible, and it took work.

**And the reward for finishing it is a better-worded paragraph.**

That is the whole problem. A player who studies a lineage to *Complete* gets prose where they used
to get hedges. Nothing they can carry, spend, or act with. So the loop is self-terminating: you
study one animal to see the system work, and then there is no reason to study a second one, because
you already know what the ending looks like.

Everything below is one answer to that: **make a characterised genome into something you can
become.**

---

## 2. The shape of it

> Find an animal → study it until you understand it → take what it has → go looking for something
> that has something else.

The player-facing object is a **tree**: six branches, three depths each, drawn in the field guide.
Every node names something you could become. Most of them are locked, and each locked node states
exactly what would unlock it.

That is the entire interface. It is a tech tree, which every player already knows how to read, and
it is doing the one job the design actually needs done — turning "go outside and look at animals"
into a legible list of reasons to.

### The rule that keeps it from being a levelling system

`MD/ROADMAP.md` rejected *authored tiers with random traits* on the grounds that it is "a levelling
system wearing a costume — nothing emerges that wasn't written down first". That objection is
correct, and it applies to any tree, including this one, unless the tree is built to dodge it.

So:

**The tree is authored. Nothing in it is.**

A node does not contain a buff. A node contains *permission* — the right to carry a trait of a
certain kind, up to a certain strength. What that trait actually is, how strong it gets, and what
else comes attached to it are all supplied by an animal the player found in a world nobody wrote.
Two players who fill in the same tree end up as different creatures, because the tree is a set of
empty shelves and the world stocks it.

Everything below is the smallest set of rules that makes that true.

---

## 3. Rule 1 — potency is the donor's actual value

Splicing `SPEED` does not give you "the speed buff". It gives you *that lineage's* speed. A lineage
sitting at 0.52 gives you almost nothing; one that has spent forty generations being chased across
open ground sits at 0.88 and gives you a great deal.

This single rule is what converts looking at animals into progress, because the only way to know
whether a lineage is worth splicing is to **characterise it and compare**. The guide already stores
decoded per-lineage values, so it can rank them. That turns the field guide from an encyclopedia
into a leaderboard, and a leaderboard is a reason to keep collecting.

It also means the good donors are *rare and located*. The fastest lineages are in open biomes under
predation; the most armoured are where predation is heaviest; the brightest are underground; the
most heat-tolerant are wherever `RegionClimate` put a hot region. The ecology already produces those
gradients. This design invents none of them — it only gives the player a reason to care where they
are.

---

## 4. Rule 2 — you take the linkage block, not the gene

`Mutation.crossover` already cuts the genome at a few points so linked traits travel together; that
is why offspring look like plausible children. Splicing uses the same unit. **You cannot cherry-pick
a locus. You adopt a contiguous block of the donor's genome and you get all of it.**

The `Gene` enum is already ordered by region, so a block is thematically coherent, and the six
blocks are the six branches of the tree:

| Branch | Contains | Headline locus |
|---|---|---|
| Physiology | `DIET`, `METABOLISM`, `SPEED`, `STAMINA` | `SPEED` |
| Disposition | `AGGRESSION`, `SOCIABILITY`, `FEAR`, `CURIOSITY`, `TERRITORIALITY` | `AGGRESSION` |
| Climate | `TEMP_PREFERENCE`, `HUMIDITY_PREFERENCE`, `ARMOR` | `ARMOR` |
| Colour | `HUE`, `SATURATION`, `BRIGHTNESS`, `PATTERN_*`, `COUNTERSHADING` | *cosmetic* |
| Light | `BIOLUMINESCENCE`, `GLOW_REGION`, `GLOW_HUE` | `BIOLUMINESCENCE` |
| Habit | `BURROWING`, `NEST_BUILDING`, `GRAZING_IMPACT` (see §11) | `BURROWING` |

So the question is never "which animal is fastest". It is "which animal is fast **and** does not
also make me starve" — and answering that requires several lineages on file, compared.

**The package is shown in full before the player commits.** This is the one change to how the
previous draft handled it, and it matters: an unadvertised drawback is a gotcha, and a player who
gets burned by one stops experimenting. A drawback the player read, weighed and accepted is the
actual game. The guide already knows every value in the block for any lineage at *Complete*, so the
splice screen lists all of them and the player picks the trade they want.

It is also honest genetics, which is worth something in a mod whose entire premise is that the
biology is real.

---

## 5. Rule 3 — depth is earned by finding better examples

Each branch has three depths, and a depth caps **how strong a value you may carry**, not what kind:

| Depth | Carries up to | Unlocked by |
|---|---|---|
| Trace | 0.45 | 1 lineage at *Complete* with a headline value ≥ 0.45 |
| Expressed | 0.75 | 3 lineages at *Complete* with a headline value ≥ 0.75 |
| Dominant | 1.00 | 5 lineages at *Complete* with a headline value ≥ 0.75 |

This is the load-bearing rule, and it is what replaces the previous draft's stability pool.

**The right to carry a strong trait is earned by proving you can find strong examples of it.** That
is one sentence. It needs no currency, no pool, and no bookkeeping the player has to track. It is
self-balancing without tuning, because a trait the ecology rarely produces is automatically slow to
unlock — the rarity of the gene *is* the price of the node, and nobody had to set it. And it points
the player at exactly the behaviour this whole document exists to cause: not one animal studied to
completion, but a survey.

A locked node therefore reads as a progress bar over real work: *Light · Expressed — 1 of 3
lineages*. The guide can even name the one it already has.

> **The numbers are placeholders.** 1 / 3 / 5 and the 0.45 / 0.75 cuts have to be set by measuring
> what the population actually produces — `gradle diversityReport` exists for this. `BIOLUMINESCENCE`
> is `constrained` with a 0.82 expression threshold, so bright lineages are already uncommon and the
> Light branch will bind far harder than Physiology at identical numbers. Measure before choosing.

Note that *Dominant* asks for more evidence rather than rarer evidence — five lineages at the same
0.75 bar that *Expressed* wanted three of. That is on purpose. A cut the ecology can barely reach
would make the top of a branch a lottery on world generation; a count the player can always work
toward makes it a survey they can finish.

### The endgame falls out of this rule for free

If the world holds only three lineages above the bar and the node wants five, you cannot buy the
other two. You have to **make** them: cull the dim ones, protect the bright ones, move a breeding
group somewhere that selects for it. `MD/ROADMAP.md`'s M5 intervention tools become the way a player
manufactures a donor, and directed evolution over a dozen generations becomes something they do
deliberately rather than something that happens near them.

The previous draft named this as an aspiration in its own section. Under the depth rule it stops
being an aspiration and becomes the natural consequence of a requirement the player can read off the
screen — which is a better place for the mod's real subject to live. At that point selection
producing things nobody wrote down is something the player is *using*, not merely watching.

---

## 6. Rule 4 — slots force the choice between branches

Depth caps make you go deep. Slots make you choose.

- You have **2 gene slots**, and each holds one block from one donor.
- Taking any branch to **Dominant** grants **+1 slot**, to a maximum of 5.

That second line is the progression curve in one sentence: specialising buys breadth. The player who
masters one branch earns the room to dabble in another, so the endgame is wide rather than merely
tall, and the only way to reach it is to do the thing the mod is about.

**Reversion is free**, and deliberately so. The previous draft's partial refund and "scar tissue"
existed to stop churn, but they were also the part that could punish a player for experimenting.
Slots and caps already prevent accumulation, which was the only real problem, so nothing needs to be
consumed. The brake on hot-swapping is friction rather than loss: reversion runs a cycle at the
splicer the way `GeneLabBlockEntity` already runs one, so you cannot re-spec halfway down a cave.

This also settles the previous draft's own note that permanent choices in a one-save survival world
are unkind. They are, so there are none.

---

## 7. Rule 5 — splice early and the report's hedge becomes real

A splice taken from a lineage below *Complete* lands somewhere inside the error bars the report
already shows — `DecodeAccuracy` hedges at *Fragmentary* and *Partial*, states a figure with a
tolerance at *Referenced*, and reads like debug output at *Complete*. Splice early and the value you
get is drawn from the range you were shown.

**The hedge in the report becomes literal mechanical risk**, which retroactively makes the whole
existing accuracy system mean something. It is the cheapest thing in this document to implement and
possibly the best, and it is why every unlock condition above says *at Complete*: you can act on a
guess, but you cannot bank one.

---

## 8. What was cut from the previous draft, and why

Recorded so the reasoning survives if someone wants a piece of it back.

- **The genomic stability pool.** A per-player resource, spent in proportion to distance from wild
  type, partially refunded on reversion. Replaced by slots and depth caps, which do the same job —
  prevent accumulation, force choice — with two visible integers instead of a hidden budget. The
  pool's one unique property was letting *how far you have already strayed* raise the price of
  straying further, which is a genuinely good idea and still not worth a system the player has to
  model in their head.
- **The novelty discount** (first-in-world characterisations cost less stability). Nothing left to
  discount once stability was gone. The sentiment is right and belongs where it already lives — an
  advancement, next to `WATCHED_IT_SPLIT`.
- **Four machine tiers** (bench → retrofit chamber → deep splice). Progression now lives in the
  tree, which the player can see, rather than in a build order, which they cannot. One block.
- **Branches gated behind tiers.** All six are visible from the first splice. A tree whose locked
  nodes are hidden is not a shopping list, and the shopping list is the point.

---

## 9. What to build first

A vertical slice, small enough to throw away, in this order — and the order matters, because the
first item is worth building **even if the other two never are**.

1. **The tree screen.** A *Self* tab in the field guide drawing all six branches and all eighteen
   nodes: every locked one stating its condition and its progress, every unlocked one showing the
   best donor on file with its value and its full package. Nothing behind it. A list of what you
   could become, mostly empty, is already a reason to go outside — and it is the cheapest possible
   test of whether this design motivates anybody, because it can be built before a single trait
   works.
2. **One branch, end to end: Light.** Visible, harmless, already has a rendering path
   (`DynamicLightsCompat`, `GlowRegion`), and rewards a cave expedition — so it exercises "go
   somewhere specific" on day one, and it has no combat value, so the loop can be proven before the
   balance problem has to be solved.
3. **One block and one store.** A splicer that consumes a genome report and applies over time the
   way `GeneLabBlockEntity` already does, and player splices stored alongside `PlayerGuideData`,
   which is already per-player and already synced.

---

## 10. Risks and open questions

- **Balance.** Minecraft player buffs trivialise Minecraft. Slots and depth caps are the intended
  brakes and they are untested. The slice above is deliberately a trait with no combat value.
- **`SPEED` and friends are ecology genes, not player stats.** They mean something specific to
  `EnergyBudget` and the AI. Mapping them onto player attributes is a translation, and a bad
  translation would make the guide lie about what a splice does. The mapping must be one table in
  one file, and the guide should quote *that* table.
- **Does the tree make the world feel like a checklist?** The honest risk of any tech tree, and the
  price of the legibility it buys. The mitigation is that no node names an outcome — *Light ·
  Dominant* does not tell you what colour you will glow or what you will give up to do it, because
  nobody knows until you find the animal. Watch for players describing the tree as "completed", and
  treat it as a warning: that would mean the shelves are reading as the goods.
- **Multiplayer.** Splices, slots and the tree are per-player; `PlayerGuideData` shows the shape.
  But `GenomeLibrary` is world-scoped and framed as "what this world's science knows", so one
  player's study work unlocking another player's nodes needs a decision. It probably should — that
  framing is the argument, and a shared tree lets a party divide the survey between them.
- **Does this cannibalise the ecology?** A player who spends their time breeding donors is not
  watching the world evolve on its own. Probably fine — it is the same system either way — but worth
  watching, because the mod's best moments are the ones nobody caused.

---

## 11. What building it changed

Two things the design got wrong, found by writing it.

**`SUBTERRANEAN` cannot be in the Habit block.** §4 lists it there and it belongs there by meaning —
a preference for the dark is exactly the drawback a digging animal should carry. It is impossible.
`SUBTERRANEAN` was appended at the end of the `Gene` enum long after the habit loci, and ordinals are
the wire format `Genome.decode` reads by index, so moving it would silently reinterpret every genome
ever saved. Habit therefore runs `BURROWING`..`GRAZING_IMPACT` and takes its cost from
`GRAZING_IMPACT` instead. The general rule is worth keeping in mind for any future block: a linkage
block has to be contiguous *in memory*, not merely coherent in meaning, and the enum cannot be
reordered to make it so.

**Two branches had no cost, and one of them was hiding it.** A test asserting that every
stat-carrying branch can cost the player something failed on Physiology first — exhaustion is stored
as a positive number and is a drawback, so reading the sign alone painted "+80% food burn" in the
same green as "+25% speed". Fixing that exposed the real one: Climate was three benefits in a coat.
It is now two-sided — `TEMP_PREFERENCE` and `HUMIDITY_PREFERENCE` are measured against a wild-type
midpoint, so a donor off a hot region shrugs off fire and one off a cold region catches *worse* than
wild type. Which animal you take the armour from decides whether the rest of the block is a gift or a
bill, which is what §4 was asking for in the first place.

Colour and Light remain deliberately costless — the tutorial branch and the vertical slice — and pay
only the slot they occupy.
