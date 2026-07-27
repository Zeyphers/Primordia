# Primordia — design beyond Milestone 1

M1 (a creature that generates, meshes and walks) is built. This document records the design for
the rest, and in particular the evolution decision that was delegated during planning.

---

## The evolution model

**Real generational genetics, with a lifetime-adaptation layer on top.**

The alternatives were considered and rejected:

- *Authored tiers with random traits* is predictable and easy to balance, but it is not evolution —
  it is a levelling system wearing a costume. Nothing emerges that wasn't written down first.
- *Lifetime-only mutation* (Spore's Creature Stage) gives fast feedback but has no inheritance, so
  populations never actually diverge and there is nothing to discover on a second playthrough.

Real genetics is the only option where the interesting outcomes are ones nobody designed. The cost
is that it is slow to observe, which is exactly what the second layer fixes.

### Layer 1 — inheritance (implemented, `genome/Mutation`)

Offspring inherit a genome via block crossover plus plasticity-weighted point mutation, with rare
macro-mutations on structural loci. Three properties matter:

- **Linkage.** Genes are cut at a few points rather than shuffled independently, so all the leg
  genes tend to travel together. Children look like plausible children, not noise.
- **Per-gene plasticity.** Colour drifts fast, limb counts drift slowly. This is what keeps a clade
  visually recognisable over dozens of generations while still letting it change.
- **Evolvable evolvability.** `MUTABILITY` is itself a gene, so lineages under pressure can evolve
  to mutate faster.

Speciation is automatic: when a child's genetic distance from its parent exceeds
`SPECIATION_DISTANCE`, it is assigned a fresh lineage id. That is what the field journal will draw
its clade tree from.

### Layer 2 — lifetime adaptation (M3)

Genetics alone operates on a timescale the player will never sit through. So a creature also
carries a small set of **epigenetic modifiers** that shift within its own lifetime in response to
what it actually does — an animal that eats a lot of meat thickens its jaw, one that runs
constantly leans out.

These modifiers are **visible immediately** and are **partially heritable** (a fraction bleeds into
the offspring's genome). This is Lamarckian and biologically wrong, and it is the right call for a
game: it gives the player same-session feedback that their intervention mattered, while the honest
Darwinian layer underneath still does the real work across generations.

### Timescale

Selection needs to be legible. Target: a visible trait shift in a population within **1–2 in-game
days** of a sustained pressure change (a new predator, a food source removed). That means short
maturation, high fecundity, and high mortality — r-selected by default, with `LIFESPAN` and
`FECUNDITY` letting individual lineages move along the r/K axis.

---

## M2 — Ecology

The food web is the selection pressure. Without it, mutation is a random walk and nothing evolves
in any direction.

- **Diet** is a continuous axis (`Gene.DIET`), not a category. 0 is pure herbivore, 1 pure
  carnivore, and the interesting animals sit in the middle.
- **Energy budget.** Creatures spend energy on movement, growth and reproduction, scaled by
  `mass` and `METABOLISM`. Big armoured creatures cost more to run, which is what stops runaway
  gigantism without a hard cap.
- **Predation** resolves from a matchup of the attacker's jaw and speed against the defender's
  armour, size and speed — all already-decoded body-plan quantities, no separate combat stats.
- **Starvation and death** feed back into the genome pool. This is the actual selection step;
  everything else is bookkeeping.

Natural spawning also lands here: founder populations seeded per biome, with `TEMP_PREFERENCE` and
`HUMIDITY_PREFERENCE` deciding who takes hold where. Different biomes should diverge into visibly
different faunas without any per-biome authoring.

## M4 — World impact

Creatures change the world, and the changed world changes what survives:

- **Grazing** consumes vegetation blocks at a rate set by `GRAZING_IMPACT`. Overgrazing strips a
  region, the herbivores starve, and the population crashes — a boom-bust cycle nobody scripted.
- **Burrowing** (`BURROWING`) digs tunnels and dens, which become shelter that shifts predation
  odds.
- **Nests** (`NEST_BUILDING`) are persistent blocks holding eggs; they make reproduction spatial,
  so territory starts to matter.
- **Trails** — repeated traffic degrades grass to dirt to path, so herd routes become visible in
  the terrain.

**Guard rails.** Terraforming is bounded: creatures never touch player-placed blocks, never break
anything above a hardness threshold, and per-chunk change budgets keep a bloom from eating a
region. The world should look lived-in, not griefed.

## M5 — The naturalist's tools

The player is an observer who can intervene, so the tooling is the actual interface to everything
above:

- **Genome scanner** — point at a creature, read its traits, lineage and generation.
- **Field journal** — a lineage tree per clade, with trait charts over generations. This is where
  evolution becomes *visible* rather than merely happening.
- **Intervention** — introduce food, cull, relocate, and (late) splice a gene directly. Each is a
  way to apply a selection pressure and then watch what it does.

---

## Open questions

- **Off-screen simulation.** Populations in unloaded chunks currently freeze. An abstract
  population-level sim (numbers and mean genomes per region, no entities) would let the world keep
  evolving where the player isn't. Large effort, large payoff — deferred until the loaded-chunk
  ecology is proven.
- **Multiplayer.** Genome replication already works, but the ecology tick is server-authoritative
  and untested under load.
- **Balance surface.** Once M2 exists there will be a lot of numbers. They should live in one
  config file, not scattered through the code.
