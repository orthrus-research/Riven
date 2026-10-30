# Art direction: Survey Plate

Survey Plate is the visual language of every block Riven draws and of every vanilla block in the
Survey Plate resource pack. It reads as a hand-colored geological survey plate drawn in pixels:
large connected forms, a dark ink line only where forms meet, color in shared ramps, and a world
muted enough that ores, gems, water and light carry the color. It stays Minecraft (native 16 x 16
faces, crisp pixels, block-sized forms, light from the upper left) and stays out of realism (no
photographic texture, no micro-noise, no painted depth).

Every block is redrawn in it: Riven's own blocks, every set received from Riven Resources
(Decoration Library 01 and Requisitions 02 to 07) and the 565 vanilla textures in
[`art/vanilla-blocks.json`](art/vanilla-blocks.json). Items, mobs, the GUI and the sky keep
vanilla's art.

| File | Holds |
| --- | --- |
| [`art/survey-plate.json`](art/survey-plate.json) | the ramps (source of truth), the step rule, the tiers and the color script |
| [`art/survey-plate-palette.json`](art/survey-plate-palette.json), [`art/survey-plate.gpl`](art/survey-plate.gpl) | every ramp's five colors, generated |
| [`art/vanilla-blocks.json`](art/vanilla-blocks.json) | each vanilla texture's group, test kind, size, animation, tint, emission and labPBR flag |
| `tools/content/survey_plate.py` | `palette`, `inventory`, `sheet` (ramps, color script, construction plates) and `check` (the acceptance tests) |

## The language

1. **Forms, not noise.** A face is built from three to six large connected forms (stones, beds,
   bark plates, leaf groups, crystal faces), each with a body tone and at most one light and one
   shadow step inside it. Coarse rocks may use up to ten smaller grains. No dither, no
   checkerboard, no scattered single pixels except deliberate glints.
2. **Ink describes, never encloses.** The darkest step of the material's own ramp marks where
   forms meet and turn from the light: cracks, joints, bedding planes, mortar, the undersides of
   leaf groups, crystal edges away from the light. Ink is one pixel wide (two at a junction), sits
   on the lower-right side of a boundary, and breaks: no form is closed on all sides, and no tile
   edge is drawn as a frame. Built objects with real frames (doors, trapdoors, glass, chests,
   functional blocks) are the exception.
3. **One light.** Light comes from the upper left inside the face: the upper-left lip of a form
   takes the light step, its lower-right rim the ink. The game shades faces by direction; no face
   carries a gradient across it.
4. **Ramps, not colors.** Every pixel is a step of one ramp of the master palette: ink, shadow,
   body, light, highlight. Shadows lean cool and lights warm, so no ramp is neutral gray. A natural
   face uses at most two ramps; a built face three.
5. **A saturation budget.** Chroma rises by tier: ground (rock, soil, sand, ice, wood) lowest,
   then living things, made things, blossoms and fruit, and signals (ores, gems, fluids, light,
   redstone, portals) highest. A gem is the most saturated thing in its cave.
6. **A color script.** Surface color follows climate and underground color follows the descent,
   so a player reads where they are from color alone (below).
7. **Quiet at distance.** A face's mean color is its distant color: Distant Horizons and mipmaps
   show little else. Quadrants of a face match in lightness, so walls do not grid, and a main face
   and its alternates share one mean.

## Palette

Each ramp is three numbers in OKLCH (a perceptual lightness, chroma and hue space): the body's
lightness, chroma and hue. The step rule in `survey-plate.json` derives the other four steps:
lightness offsets of -0.25, -0.12, +0.09 and +0.17 (compressed into the room left between 0.22
and 0.96, at least 0.03 apart), chroma at 0.55, 1.05, 0.9 and 0.7 times the body's, and the hue
turned up to 20 degrees toward blue (265) in the darker steps and up to 18 toward yellow (85) in
the lighter ones. Colors outside sRGB lose chroma until they fit.

| Tier | Body chroma up to | Ramps |
| --- | --- | --- |
| ground | 0.09 | 16 rocks, 8 soils, sediments and ices, 4 woods, end stone, 17 fired clays |
| living | 0.13 | 10: four foliage, grass, moss, autumn, fungus, mycelium, and `pretint` |
| made | 0.17 | 9 built materials and 16 dyes |
| bloom | 0.20 | 7 petal and fruit colors |
| signal | gamut | 13 gem families, redstone, 3 native metals, water, lava, ember, glow, sea glow, portal, ender |

A face may use two ramps outside the signal tier (three when framed), two signal ramps for its
accents, and eight colors (ten when framed). A new ramp is added by its three numbers in
`survey-plate.json` and the palette regenerated (`survey_plate.py palette`); a hand-picked color
off a ramp is never added.

Textures the game tints by biome (grass, leaves, vines, lily pads, ferns, stems; 28 flagged
`tinted`) use the `pretint` ramp, nearly neutral but hue-shifted, so the tint keeps a warm light
and a cool shadow. The two colormaps are redrawn as the surface color script; they are smooth
lookups, not faces. Redstone dust is tinted by power in code and keeps vanilla's tint.

## Color script

Host rocks keep their family wherever they lie; the descent reads in color because the geology
changes with depth. Blocks that belong to one depth biome or realm (its overlays, floors, growths
and boundary rocks) take that realm's key.

| Realm | Ramps | Reads as |
| --- | --- | --- |
| Skin | humus, stone, felsic | soil browns over cool gray stone and pink granite |
| Layered Deep | umber, carbonate, sandstone | ochre, cream and umber beds |
| Broken Basement | basement, felsic, slate | blue-gray gneiss with pink feldspar |
| Molten Root | mafic, obsidian, ember | green-black rock lit oxblood and orange |
| The Beneath | serpentine, pale, garnet | teal serpentine, pale granulite, red garnet |
| Infernal Deep | infernal, cinder, ember | red-black rock, violet ash, embers |

| Climate | Ramps |
| --- | --- |
| Frozen | snow, ice, needle |
| Cold | needle, podzol, moss |
| Temperate | leaf, grass, humus |
| Dry | olive, ochre, sand |
| Warm and wet | canopy, redbed, moss |
| Turning (climate edges, treelines) | autumn, bloom red, bark |

`survey_plate.py sheet` draws the ramps by tier, both scripts and three construction plates.

## Materials

| Family | Forms | Ink | Notes |
| --- | --- | --- | --- |
| Directionless rock | 4 to 10 interlocking grains or blocky forms | joints and grain contacts | quarter turns allowed after review |
| Bedded and foliated rock | lenses and beds, broken and of uneven thickness | bedding planes, cleavage | sides bedded, tops a plainer bedding plane; no side quarter turns |
| Soil, sand, gravel | clods, ripples, pebbles of a few sizes | pebble undersides only | sand nearly ink-free; no grain-per-pixel |
| Snow and ice | drifts, crusts; ice as panes with bubbles | snow none; ice fracture lines | ice translucent; packed ice opaque |
| Bark and end grain | plates along the axis; rings | furrows; ring breaks | species keep their bark identity |
| Planks | three or four boards with grain runs | board joints | species hue from its wood ramp |
| Foliage | overlapping leaf groups with shaped sky gaps | under each group | needles, fronds and blades keep their anatomy |
| Crossed plants | silhouette first: stems as single strokes | on the shadow side of a stem or petal | standing pieces meet the bottom row |
| Crystals and gems | two or three faces per crystal: lit, body, shadow | facet edges away from the light | one glint at most; raw, never cut |
| Ore overlays | the habit's grains, veins or masses | grain rims on their shadow side only | readable by lightness, not hue alone |
| Metals | broad bands with one bright step | seams | metal blocks are built: panels, rivets |
| Masonry | blocks of the masonry ramp, worked flatter than raw rock | mortar joints | cracked and mossy states keep the bond |
| Joinery | boards, frames, hinges | frame and panel edges | doors read as one object across both halves |
| Dyed goods | weave (wool), pour (concrete), grain (powder), fired clay | weave rows; pour lines | every dye stays its name |
| Functional and redstone | an emblem on the working face | object edges | states read by lightness: powered and lit faces take the light steps |
| Light sources | a lit core in the light steps | none in the core | emission in labPBR |
| Glass | a frame and one or two glints, open center | the frame | stained glass translucent; panes match |
| Fluids | slow bands and ripples | none | animation frames as vanilla's |
| Fire, portals, breaking | flame tongues; swirls; cracks | the crack stages | destroy stages draw on any face |

Glazed terracotta becomes sixteen survey-map legend tiles, one symbol per dye (strata hatching,
contact lines, fold symbols, dip arrows, ore and fossil marks), drawn so four rotated tiles join
into a larger figure as vanilla's do. Block entities (chests, beds, shulker boxes, signs, banners,
the enchanting book, beacon and gateway beams, the end portal) are UV sheets: they follow the
palette and the ink rule, keep vanilla's layout and sizes, and are reviewed on their models.

## Shader maps

labPBR 1.3 maps (`_n.png`, `_s.png`, same size and frames as the color texture) for the textures
where light matters: gems, ores and gem rock, native metals and metal blocks, ice, glass, wet and
water-polished surfaces, fluids, and every block that gives light (108 vanilla textures are
flagged `pbr`; Riven's sets mark theirs the same way).

- `_n`: red and green the normal's X and Y in DirectX order (green down), blue the ambient
  occlusion (255 none), alpha the height (255 at the surface, at least 1). Normals come from a
  hand-drawn height map in which forms stand and ink sinks, never from the color's lightness.
- `_s`: red perceptual smoothness; green F0 from 0 to 229, or 230 to 237 for iron, gold,
  aluminum, chrome, copper, lead, platinum and silver; blue porosity (0 to 64) or subsurface
  scattering (65 to 255, for ice, leaves and wax-like stone); alpha emission from 0 to 254 (255
  emits nothing), only on blocks Riven or vanilla gives light, in proportion to that light.
- Check each under BSL and Complementary Reimagined (pinned in `runtime.lock.json`) at day,
  night and in a cave before scaling.

## Delivery

Riven's own blocks ship in the Riven jar as today. The vanilla replacements ship as a separate
resource pack, Riven Survey Plate, with each release, at vanilla's paths and sizes, so blocks
keep their IDs, recipes and GTCEu stone types; prepared instances turn it on, and other packs may
leave it off. GTCEu's ore stencils (the overlays its ores draw on vanilla stone types) join the
pack under GTCEu's paths when GTCEu is loaded. Riven Resources builds both from its productions;
Riven imports them unchanged.

## Acceptance tests

`survey_plate.py check KIND PNG...` runs these on native 16 x 16 textures; the limits live there
and here and change together. Kinds are `face`, `framed`, `overlay`, `fluid`, `translucent`,
`standing` and `hanging`, as each inventory entry names. Block entity sheets, colormaps and the
32-wide flowing fluids are checked for the palette by eye and reviewed on their models.

| Test | Limit | Applies to |
| --- | --- | --- |
| Palette | every opaque pixel is a palette color | all |
| Alpha | 0 or 255 only | all but translucent |
| Ramps | 2 outside signal (framed 3), 2 signal | all |
| Colors | 8 (framed 10) | all |
| Noise | at most 12% of non-signal pixels without a same-color neighbor | all |
| Forms | mean region at least 4 pixels, largest at least 24 | face, framed |
| Ink share | 4% to 22% of pixels | face, framed |
| Ink lines | at most 10% of ink pixels with three ink neighbors | all with ink |
| No frame | no edge row or column half ink | all but framed |
| Seams | 80% of ink touching an edge continues across it (within a pixel) | tiling kinds |
| No ink | none | fluid |
| Value span | lightness range at least 0.22 | face, framed, standing, hanging |
| Balance | quadrant mean lightness within 0.045 | face, framed, fluid |
| Swatch | mean within 0.08 (OKLab) of its main ramp's body | face, framed, fluid |
| Attachment | standing reaches the bottom row, hanging the top | standing, hanging |
| Contrast | median lightness step over its host at least 0.12 (`--host`) | ore and gem overlays |
| Alternates | means within 0.025, same ramps (`--alternate`) | every alternate |

Measured on existing art these separate the languages: vanilla's stone, dirt and oak planks fail
the palette, forms and ink tests, and dirt the noise test too (33% of its pixels stand alone, 96%
of oak leaves'); Requisition 06's host stones are quiet but fail the palette, ink and value tests
(gabbro spans 0.13 in lightness). The sheet's construction plates pass every face test. Passing
is necessary, not sufficient: walls, mixed variants, model views and the in-game look still
decide.

## Calibration and self-gating

Riven Resources gates its own productions against this document. A production starts with a
calibration set: vanilla stone, dirt, grass (tinted), oak log and planks, oak leaves, sand,
gravel, stone bricks, red wool, glass, water, a furnace, the diamond and iron ores, and from
Riven gabbro, limestone (bedded), a Riven tree's bark and leaves, one crossed plant, one ore
overlay over three hosts, one gem growth and one glowing rock. Calibration passes when every
texture passes `check`, every face holds in a 16 x 16 wall and in a mixed-variant wall, the set
reads as one family on a contact sheet beside the vanilla and old Riven faces it replaces, and
the shader maps behave under both pinned packs. Limits may move during calibration only, in
`survey_plate.py` and this document together, with the reason in the production's decisions
file; they are then frozen for the production. The review standard of Riven Resources (single
native texture first, gapless walls, model rotation) still applies.

Open: the ink floor (lightness 0.22) leaves the darkest ramps' ink nearly black; calibration
decides whether dark rocks need a lighter ink.
