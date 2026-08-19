# GUI design files

Edit these, hand them back, and they get wired up. Nothing here ships — the mod reads the copies
under `src/main/resources/assets/primordia/textures/gui/`.

---

## `field_guide.png` — the guide's page

**512×256. The guide uses the top-left 320×214.**

Everything static about the page lives here: the parchment, the frame, the rule under the heading,
and the recess the specimen is drawn into. Weather it, stain it, age the edges, put a coffee ring on
it — anything a paint program can express belongs in this file rather than in a fill call.

| Region | Where | Leave clear of |
|---|---|---|
| Page body | `0,0` → `320,214` | — |
| Heading | `14,4` → `306,18` | tab title is drawn here |
| Rule | `14,19`, 292 wide | — |
| Specimen recess | `182,26`, 124×166 | the animal renders inside this |
| Text column | `14,26` → `306,~190` | keep readable; ink is dark brown |
| Page number | bottom right, ~`14` up from the base | — |

The page is drawn at panel coordinate `(0, 26)` — the 26px above it is the tab strip, which is
**not** in this texture because tabs have an active and an idle state and are drawn in code.

Two rules worth respecting:

- **Keep it legible under text.** The whole left two-thirds carries body text in a dark brown.
  Heavy staining there costs readability, which is what the last three rounds of work were about.
- **Don't move the recess.** Its position is duplicated in `FieldGuideScreen`; if you want it
  somewhere else, say so and I'll move both.

---

## `sample_cooler.png` — the cooler's screen

**256×256. The screen uses the top-left 176×148.**

Cut from vanilla's shulker box panel and rebuilt: the title bar and two rows of eight recesses,
then the player's inventory. It is a whole panel, not a patch over a vanilla one, so repaint it
however you like — frost the metal, ice the recesses, put a temperature gauge in the space beside
the grid.

| Region | Where | Note |
|---|---|---|
| Panel | `0,0` → `176,148` | anything past this is not drawn |
| Title | `8,6` | "Sample Cooler" is drawn over this |
| Cooler slots | `16,18`, 8 × 2 of 18px | recesses centred; slot squares are 16×16 |
| "Inventory" label | `8,54` | drawn in code |
| Player inventory | `8,66`, 9 × 3 | vanilla spacing |
| Hotbar | `8,124`, 9 wide | — |

**The slot positions are duplicated in `SampleCoolerScreenHandler`.** Move a recess in the paint
and the clickable square stays where it was — say where you want them and both get moved together.

---

## `art.png` + `layout.png` — the Gene Lab's screen

`art.png` is the picture. `layout.png` says where the interactive parts are: seven coloured blocks
you move to mark slots and progress lines.

| Element | Colour |
|---|---|
| Sample slot | magenta `255,0,255` |
| Fuel slot | red `255,0,0` |
| Redstone slot | green `0,255,0` |
| Output slot | blue `0,0,255` |
| Line 1 | yellow `255,255,0` |
| Line 2 | cyan `0,255,255` |
| Line 3 | orange `255,128,0` |

Slots must stay 16×16. Lines can be any shape — they were read back as elbowed routes last time and
the fills follow the corners.

```
python design/gui/read_layout.py
```

reads the markers, checks each is a solid rectangle, and prints the Java constants.

---

## Guide text

`design/export_guide.py` writes every field-guide entry to
`~/Downloads/primordia_field_guide.json` — titles, paragraphs, unlock conditions and the tab
structure, parsed out of the source so it cannot drift. Edit that file and hand it back.

---

## `splicer_layout.png` — the Splicing Bench's screen

**248×256, and the canvas size *is* the screen size.** Make the image bigger and the GUI gets
bigger; nothing in code hardcodes the dimensions.

This one is a layout file, not art. Move the coloured blocks so each covers the thing it names, save,
and run:

```bash
python design/gui/splicer_layout.py
```

It prints every element's position and complains if a marker is missing or the wrong size. Hand the
png back once it reads the way you want and the screen gets rebuilt from it.

| Marker | Colour | What it is | Size |
|---|---|---|---|
| `ROW1`–`ROW6` | magenta → green | the six trait rows, top to bottom | any |
| `RAIL` | cyan | the track the progress line runs down, rows → output | any |
| `OUTPUT` | blue | the slot the finished serum appears in | **16×16** |
| `INV` | spring green | the player's 3×9 inventory block | **162×54** |
| `HOTBAR` | azure | the player's hotbar | **162×18** |
| `TITLE` | violet | top-left of where the screen title is drawn | **8×8** |

Three rules:

- **Nothing but a marker may use those exact colours.** The reader finds elements by colour alone.
- **Each marker stays a solid rectangle.** It is measured by its bounding box, so a stray pixel of
  the same colour in a corner silently stretches it across the screen.
- **The four sized markers must keep their size.** Slots are 16×16 and the inventory grid is fixed
  by vanilla's spacing; the reader will tell you if one has drifted.

The rows may be any height and the rail any width — those are free, and the rail is what the
progress line follows from each row down to the output slot.
