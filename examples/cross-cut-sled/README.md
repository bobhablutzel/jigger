# Cross-cut sled

A table-saw cross-cut sled — MDF base with butt-joined front and rear
fences, plus a laminated handle profiled with a curve. Demonstrates
spline cutouts (the curved handle), a stop-block T-slot, and Cadette's
joint inference.

## Run it

```
create crosscut_sled S w 70cm l 60cm fh 12cm
```

Parameters:

| Name | Shorthand | Default | What it controls |
|------|-----------|---------|------------------|
| `width` | `w` | (required) | Sled width along the saw blade |
| `length` | `l` | (required) | Sled length perpendicular to the blade |
| `fence_height` | `fh` | (required) | Fence thickness measured from the base |
| `stop_block_left` | `sbl` | `0` | T-slot on the left fence (off by default) |
| `stop_block_right` | `sbr` | `1` | T-slot on the right fence (on by default) |
| `handle_thickness` | — | `2` | Number of plywood laminations in the handle |

The template warns if width is under 80 cm (handle won't have safe
clearance) or fence height is under 12 cm (blade can pop through the
top of the fence).

## What to inspect

- **3D view**: the assembled sled. Orbit to see the laminated
  handle profile, the front + rear fences, the stop-block channel.
- **Parts list**: base + fence(s) + handle laminations.
- **Cut sheet**: notice the *handle* parts have a curved spline
  outline on the panel — that's the spline-cut rendering. Each
  lamination layer cuts identically.
- **`show bom`**: material totals across baltic birch plywood and
  any MDF used for the base.

## Why this example

Cross-cut sleds are a near-universal table-saw jig and a good
exemplar of "real woodworking project that benefits from a digital
build." The curved handle exercises Cadette's spline cutouts, the
T-slot exercises rect cutouts, and the laminated handle shows the
glue-up modeling pattern.
