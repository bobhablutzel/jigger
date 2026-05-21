# Fence gate

A wooden fence gate built from 2x4s — two stiles, two rails, and a
diagonal brace with miter-cut ends. Demonstrates CADette's
dimensional-lumber support: the dollar-cost packer picks the
cheapest mix of 8'/10'/12'/16' boards for the parts.

## Run it

```
create fence_gate G w 100cm h 180cm
```

Parameters:

| Name | Shorthand | What it controls |
|------|-----------|------------------|
| `width` | `w` | Outside width of the gate frame |
| `height` | `h` | Outside height of the gate frame |

Both required.

## What to inspect

- **3D view**: the gate frame with the diagonal brace fitted between
  the bottom-left corner of the bottom rail and the upper-right
  corner of the top rail. Notice the brace's two ends are
  asymmetrically mitered — each face flush against the adjacent
  framing member.
- **Parts list**: 5 parts, all 2x4 SPF (`lumber-2x4-spf`).
- **Cut sheet**: dimensional lumber appears as narrow strips. The
  packer picks the cheapest mix of standard board lengths from your
  preferences.yaml (default: 8'/10'/12'/16').
- **`show bom`**: the BOM groups by stock length (e.g., "1× 2x4 SPF
  10ft, 1× 2x4 SPF 8ft, total $7.45").

## Tweaking lumber prices

The packer picks the cheapest mix based on prices in
`~/.cadette/preferences.yaml` under `materials.lumber-2x4-spf.prices`.
Default values are sourced from Home Depot — override them with your
local lumberyard's prices to get accurate cost estimates.

## Why this example

The fence gate was the test case that motivated the dollar-cost
packer. With its 5 parts totaling about 153" of 2x4, a naive
"minimize linear feet" algorithm picks one expensive 16' board.
Real woodworkers know that 2×8' boards are cheaper, fit in a car,
and are easier to handle. CADette's optimizer agrees.
