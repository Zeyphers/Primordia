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
