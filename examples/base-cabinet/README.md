# Base cabinet

A frameless base cabinet — two side panels, a floating bottom, a
top stretcher, and a hardboard back panel. Joints are dadoes (sides
to bottom), pocket-screws (sides to stretcher), and rabbets (sides
to back). Optionally includes a recessed toe-kick.

## Run it

```
create base_cabinet K w 50cm h 60cm d 40cm
```

Parameters:

| Name | Shorthand | Default | What it controls |
|------|-----------|---------|------------------|
| `width` | `w` | (required) | Outside cabinet width |
| `height` | `h` | (required) | Outside cabinet height |
| `depth` | `d` | (required) | Outside cabinet depth (front-to-back) |
| `toe_kick` | — | `1` | Include the toe-kick notch + plate (`0` to disable) |
| `toe_kick_height` | — | `100mm` | Vertical recess height |
| `toe_kick_depth` | — | `75mm` | Horizontal recess depth |

To turn the toe-kick off:

```
create base_cabinet K w 50cm h 60cm d 40cm toe_kick 0
```

## What to inspect

- **3D view**: the cabinet with the toe-kick recess visible at the
  bottom front of each side panel. The bottom panel sits 100mm above
  the floor when the toe-kick is enabled.
- **Parts list**: 5 or 6 parts depending on toe-kick (left + right
  sides, bottom, top stretcher, back, optionally a toe-kick-front
  plate).
- **Cut sheet**: side panels show the toe-kick notch as a dashed
  rectangle with an X marking the waste region. Dadoes, rabbets,
  and overall part dimensions render as architectural dim lines.
- **`show bom`**: separate totals for 18mm plywood (sides, bottom,
  stretcher, kick plate) and 5.5mm hardboard (back).

## Why this example

The base cabinet is the foundation template that the kitchen island
example composes from. Kitchen, vanity, workshop bench — wherever a
woodworker is building a cabinet box, this template is the start.
It also demonstrates several joint types in one place: dado, pocket
screw, rabbet.

## Next: compose your own

Once a base cabinet's geometry is correct, scripts like
[`../island/island.cds`](../island/) compose multiple cabinets into
a larger assembly. Cabinets share counters, faces, and trim — the
template gives you a unit; the composition makes the kitchen.
