# Kitchen island

Two base cabinets butted together side-by-side with a single granite
counter on top. The classic multi-template composition example: each
template gives you a unit (a cabinet), and the script ties them
together into a larger assembly.

This example has two flavors:

- [`island.cds`](island.cds) — two cabinets, one counter. The
  simplest version.
- [`island2.cds`](island2.cds) — same shape, slightly different
  composition pattern.

## Run it

In the Cadette command panel:

```
run examples/island/island.cds
```

Or run the alternate variant:

```
run examples/island/island2.cds
```

## What to inspect

- **3D view**: two cabinets side-by-side with the counter spanning
  them. Orbit to see the toe-kick recess on each cabinet.
- **Parts list**: parts from both cabinets, plus the counter. Note
  the `b/...` and `b2/...` naming — each `base_cabinet`
  instantiation gets a unique prefix so part names stay distinct.
- **Cut sheet**: 18mm plywood across multiple sheets if the totals
  warrant. Granite is a "per-piece" material — not packed onto a
  sheet, just listed in the BOM.
- **`show bom`**: itemized totals for plywood, hardboard, and
  granite slab.

## Reading the script

[`island.cds`](island.cds) is short but exercises several Cadette
idioms:

- `create base_cabinet b w 80cm h 60cm d 40cm` — instantiate a
  template with shorthand parameters.
- `right of b` — placement relative to another assembly.
- `using none` — turn off the default namespace prefix (the
  cabinets get plain names like `b` and `b2`).
- `create part counter material "granite-1-1/4" ...` — a raw part
  outside any template.
- `rotate counter 90,0,0` — orient the counter flat.

## Why this example

A real kitchen has multiple cabinets composed into runs and
islands. The single-cabinet template gets you one box; this script
shows how the composition layer takes the next step. Useful as a
springboard when writing your own `.cds` scripts for actual
kitchens, vanities, or workshop benches.
