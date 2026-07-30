# Textures

What exists, what is borrowed from vanilla, and what still needs drawing.

Every entry marked **borrowed** works and looks acceptable, but is somebody else's art: it renders
whatever the player's resource pack renders, which is right for a widget (a flame means "burning"
in every pack) and wrong for an identity (a Genome Bank should not look like a bookshelf).

Regenerate any of the procedural files with the scripts noted; do not hand-edit them.

---

## Have it

### Authored (Jacob)

| File | Size | Used by |
|---|---|---|
| `textures/block/basic_gene_lab.png` | 128×128 | Basic Gene Lab, idle. Screen art at **x 84–99, y 42–49**. |
| `textures/item/biopsy_kit.png` | 16×16 | Biopsy Kit |
| `textures/item/tissue_sample.png` | 16×16 | Raw Tissue Sample, empty vial |
| `textures/item/tissue_sample_full.png` | 16×16 | Raw Tissue Sample carrying a specimen |
| `textures/gui/basic_gene_lab.png` | 256×256 (176×204 used) | Gene Lab screen, with the routed progress lines |
| `textures/gui/field_guide.png` | 512×256 (320×214 used) | Field guide page — parchment, frame, specimen recess. Editable copy in `design/gui/`. |

### Generated

| File | Size | Notes |
|---|---|---|
| `textures/block/basic_gene_lab_sequencing_tex.png` | 128×1024 | 8 frames, 3 ticks each. Base art with only the screen rectangle repainted. |
| `textures/block/basic_gene_lab_decoding_tex.png` | 128×1024 | 8 frames, 4 ticks each. Same. |
| …`.png.mcmeta` for both | — | Frame timing. |

Both are derived from `basic_gene_lab.png`. **If that file changes, these must be regenerated** —
they each embed a full copy of it. Only the screen rectangle differs between frames.

### Borrowed from vanilla

| Slot | Borrowed texture | Verdict |
|---|---|---|
| Genome Scanner (item) | `item/spyglass` | Replace — it is a distinct instrument |
| Sequence Data (item) | `item/paper` | Replace — should read as a data cartridge, not paper |
| Genome Report (item) | `item/written_book` | Replace — must not be confused with the Field Guide |
| Field Guide (item) | `item/knowledge_book` | Replace — this is the mod's front door |
| Preservation Case (block) | `block/packed_ice` + `blue_ice` + `iron_block` | Replace |
| Genome Bank (block) | `block/bookshelf` + `smooth_stone` | Replace |
| Gene Lab flame | `gui/container/furnace.png` | **Keep.** A flame means burning in every pack. |
| Field Guide pages | *(none — own texture)* | Replaced; see `design/gui/README.md` to weather it. |

---

## Need it

Ordered by how much the mod suffers without them.

### 1. Field Guide — item, 16×16
The mod's front door and the icon of its own creative tab. Currently a vanilla knowledge book, so
it reads as a vanilla feature. Wants: a field notebook — worn cover, visible bookmark or strap,
ideally a hint of green or specimen tag. Must be distinguishable from the Genome Report at a glance
in a hotbar.

### 2. Genome Report — item, 16×16
Output of every decode, so it is seen constantly. Wants: a printed sheet or fanfold readout, clearly
*paper with data on it*, clearly not a book. Pairs with (1) — the two must never be confused.

### 3. Raw Sequence Data — item, 16×16
Currently plain paper. It is named `Raw_Sequence_Data_XXXXXX.fastq` and its tooltip is base pairs,
so paper undersells it. Wants: a cartridge, tape reel, or punched strip — something that reads as
machine-readable rather than human-readable.

### 4. Genome Bank — block, 3 faces (16×16 each: top, side, front)
Currently a bookshelf. Wants: an archive cabinet or server rack — drawers, labelled spines, or
indicator lights. Its whole function is *storage of knowledge*, and it should look institutional.

### 5. Preservation Case — block, 2–3 faces (16×16 each)
Currently ice. Wants: an insulated chest or cryo unit — panelled metal, a frosted window, maybe a
temperature dial. Should read as equipment, not as a naturally occurring block.

### 6. Genome Scanner — item, 16×16
Creative-only, so lowest priority. Wants: a handheld analyser — screen, grip, small aerial.

### 7. Basic Gene Lab — screen frames *(optional)*
The animated screens are generated in the mod's own palette, sampled from the base texture. If you
want hand-drawn frames instead, supply the **16×8 screen rectangle only** (x 84–99, y 42–49) as a
strip of 16×(8·N) and the generator can composite them into the full sheet.

### 8. Advanced Gene Lab — block *(not yet implemented)*
Reserved. The tier above the Basic lab, deferred from the original pipeline design along with the
automated batch lab.

---

## Conventions

- Items are **16×16 RGBA**. Blocks are **16×16 per face** unless the model says otherwise.
- Animated textures are **vertical strips** of whole frames plus a `.png.mcmeta` giving `frametime`.
- Namespace everything `primordia:` in models; an unnamespaced path silently resolves to
  `minecraft:` and fails as a missing-texture placeholder rather than an error.
- The GUI layout is edited as art, not code — see `design/gui/`. `art.png` is the picture,
  `layout.png` marks where the slots and progress lines are, and `read_layout.py` turns the markers
  back into coordinates.

## Checking your work

```
python design/gui/read_layout.py          # GUI marker positions -> Java constants
gradle build                              # models, blockstates and lang are validated by the build
```

A missing texture shows in game as the black-and-magenta placeholder and logs a warning naming the
path it wanted — that log line is the fastest way to find a namespace typo.
