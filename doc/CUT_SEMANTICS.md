# Understanding Cuts

The `cut` command removes material from a part. Five shape variants cover almost everything you'll want to do: `rect`, `circle`, `polygon`, `spline`, and reusable named `shape`s. This doc explains the mental models behind cuts so you can read your own commands accurately and avoid the common gotchas.

For non-cut-specific scripting gotchas (parenthesis grouping, duplicate names, error-message positions, etc.), see [SCRIPTING_PITFALLS.md](SCRIPTING_PITFALLS.md).

## The mental model: cuts remove material *inside* a closed boundary

Every cut defines a **closed 2D region** in the part's local coordinate space, and removes the material inside that region. There is no such thing as an "open curve" cut — physically, a cut along a zero-width line wouldn't remove any material, so the grammar doesn't have one.

This polarity matters most for polygons and splines. If you trace a curve along the outline of the *shape you want to keep*, the cut removes the wrong region — it removes whatever is bounded by your curve plus its periodic closure, which is rarely what you intended. To shape a part with cuts, **trace the boundary of the material you want to remove**, not the boundary of the material you want to keep.

## The five shapes

| Shape | Syntax | When to use |
|---|---|---|
| `rect` | `rect at x, y size w, h` | Axis-aligned rectangles. Simplest, most common. |
| `circle` | `circle at cx, cy radius r` | Holes, cup-hole pockets, round openings. |
| `polygon` | `polygon (x,y), (x,y), ...` | Straight-edged outlines with sharp corners. |
| `spline` | `spline (x,y), (x,y), ...` | Smooth curves passing through control points (Catmull-Rom). |
| `shape` | `shape <name> at ax, ay` | Reuse a previously-named polygon or spline. |

All variants accept an optional `depth N` clause to make a partial-depth pocket instead of a through-cut.

**When in doubt, use `rect`** for axis-aligned rectangles. It's just `at x, y size w, h` — three numbers, no vertex-ordering or closure footguns. Polygon is for shapes a rectangle can't express.

## Vertex order defines the shape, not the bounding box

With polygon and spline, you supply vertices in order, and the shape closes by connecting the last vertex back to the first. **The shape is whatever you trace, in that order — including the closing segment.**

A common mistake: assuming 4 vertices automatically form a rectangle.

```
cut "panel" polygon (0, 40), (0, 20), (50, 20), (50, 0)
```

You might think this defines a rectangle, but trace it carefully: down the left edge from y=40 to y=20, across to x=50, down to y=0, then **closing diagonally** back to (0, 40). The closing diagonal crosses the middle horizontal segment at (25, 20):

```
Y
40+ A───────╲
   │         ╲
   │          ╲ closing diagonal
   │           ╲
20+ B──────X───C   ← closing diagonal crosses B→C here
   │           │
   │           │
 0+         ╲ │
            ╲│
             D
   0       25 50  X
```

The polygon edges intersect each other. The two halves get opposite winding, and only one fills, so you get a triangular cut where you expected a quadrilateral.

For an actual rectangle from those bounds, the four corners need to share y-values pairwise:

```
cut "panel" rect at 0, 0 size 50, 40
# or, if you really want it as a polygon:
cut "panel" polygon (0, 0), (50, 0), (50, 40), (0, 40)
```

**Rule of thumb:** if you find yourself drawing a shape mentally and realising the closing segment cuts across the middle, your vertex order is wrong. Walk the boundary in a consistent direction (CCW or CW) without doubling back.

## Polygon vs spline — sharp corners or smooth curves

Both take a vertex list and close periodically. The difference is interpolation.

- **`polygon`** connects vertices with **straight line segments**. Every vertex is a sharp corner.
- **`spline`** connects vertices with a **smooth Catmull-Rom curve**. Every vertex lies *exactly* on the curve (Catmull-Rom is interpolating), but the path between vertices bends smoothly through them.

A common visual surprise with splines: when you place control points at the corners of a square, the curve doesn't trace the square. It bulges *outward* from the convex hull — by about 12 mm when the square is 100 mm on a side. That's standard centripetal Catmull-Rom behaviour, not a bug. If you want the curve to follow the hull more tightly, add more intermediate control points.

To get sharp-cornered curves (smooth bends with sharp transitions), use polygon with many vertices approximating the curve, or place two near-coincident spline control points at the corner you want sharp.

## Through-cuts vs partial-depth pockets

By default, a cut goes all the way through the part. Add a `depth N` clause for a partial-depth pocket — the cut goes that deep into the front face, leaving the back face solid.

```
cut "panel" rect at 50, 50 size 30, 30           # through-cut
cut "panel" rect at 50, 50 size 30, 30 depth 10  # 10 mm deep pocket
```

Front face is the default. To pocket from the back instead, the model supports a `face` field but the grammar doesn't yet expose it — you'd need to construct the cut programmatically through a template.

## Cuts compose by union

Multiple cuts on the same part are unioned — material removed by either cut stays removed. Use this to build up complex shapes from simple primitives:

```
# Trim two corners off a panel
cut "panel" rect at 0, 0 size 50, 50              # bottom-left corner
cut "panel" rect at 550, 0 size 50, 50            # bottom-right corner

# Combine a rectangular through-cut with a curved partial-depth pocket
cut "panel" rect at 200, 100 size 200, 200        # central window
cut "panel" spline (180,90), (420,90), (420,310), (180,310) depth 3  # rebate around it
```

When two cuts overlap on a partial-depth pocket, the deeper one wins. When a through-cut overlaps a pocket, the through-cut wins.

## Named shapes for reuse

If you'd use the same outline more than once, declare it once and place it many times:

```
shape pin polygon (0, 0), (5, 0), (5, 5), (0, 5)

create part "side" size 600, 800
cut "side" shape pin at 100, 100 depth 10
cut "side" shape pin at 100, 200 depth 10
cut "side" shape pin at 100, 300 depth 10
cut "side" shape pin at 100, 400 depth 10
```

Shapes are defined at the origin (in their own local 2D space) and translated by the anchor at each call site. Polygon and spline shapes both work; the call site looks the same regardless.

## When the cut extends past the panel

Two distinct cases, two distinct responses.

**The shape doesn't actually overlap the panel** — every vertex (and every interior point) sits outside the panel face. The cut is rejected at command time:

```
> cut "panel" rect at 700, 100 size 50, 50
Cutout rect at (700.0, 100.0) cm size 50.0 × 50.0 cm through falls outside 'panel' (60.0 × 90.0 cm); ignored.
```

**The shape's bounding box extends past a panel edge but the shape itself does overlap** — the cut is accepted and clipped to the panel boundary, with an informational note:

```
> cut "panel" polygon (10, 10), (20, 10), (1500, 25)
Added cutout to 'panel': polygon (3 vertices) through
  Note: cutout extends past the panel edge; clipped at the boundary.
```

The note isn't an error — it's flagging that what gets cut may not match what you typed if you weren't expecting the clipping. Useful for catching typos like `1500` when you meant `150`.

## Coordinate system

The cut face is a 2D plane local to the part, with origin at one corner and axes running along the part's width and height. All cuts use this local frame; scene-level rotations and translations don't affect cut coordinates. A `cut at 50, 100` means "50 units along the part's width, 100 units along its height", regardless of where the part is positioned in the world.

The unit is whatever `set units` is currently configured to. With `set units cm` (the default for many users), `cut "panel" rect at 10, 5 size 20, 30` means 10 cm × 5 cm origin and 20 cm × 30 cm extent.

## Common patterns

### Trim a corner
```
cut "panel" rect at 0, 0 size 75, 75              # bottom-left toe-kick
```

### Cut a hole
```
cut "panel" circle at 300, 450 radius 25          # 50 mm dia hole
```

### Cup hole for European hinges
```
cut "door" circle at 100, 50 radius 17.5 depth 13  # 35 mm cup, 13 mm deep
```

### Define a curved profile by cutting the complement
You want a part shaped like an arch — straight bottom, curved top. The part starts as a rectangular blank; you cut away the rectangle's top corners, tracing the curved outline:

```
create part "handle" size 100, 40
cut "handle" spline (0,40), (0,20), (60,20), (70,40), (80,40), (90,20), (100,20), (100,40)
```

The spline's closed boundary encloses the rectangle's top corners plus the strip above the arch — exactly the material to remove. (Note: the spline's smooth interpolation through the (0, 40) and (100, 40) corners produces a slight rounding there, which is usually fine ergonomically. If you need crisp rectangle corners, add doubled near-coincident control points.)

### A column of shelf-pin holes
```
shape pin polygon (0, 0), (5, 0), (5, 5), (0, 5)
create part "side" size 600, 800
cut "side" shape pin at 50, 100 depth 10
cut "side" shape pin at 50, 132 depth 10  # 32 mm spacing — European standard
cut "side" shape pin at 50, 164 depth 10
# ...
```

(A `for`-loop over the spacing is the next ergonomic improvement here, but as of this doc you list them explicitly.)

## Mental model checklist

When a cut isn't doing what you expect, walk through these:

1. **Did I trace the material I want to remove, not the material I want to keep?**
2. **Does my polygon close cleanly without self-intersection?** Walk the closing segment in your head.
3. **For splines, does the curve actually pass where I think it does?** Catmull-Rom bulges outward through square corners; densify control points if needed.
4. **For partial-depth pockets, is `depth` set?** Without it, the cut goes through.
5. **For coordinates, am I in the part's local frame, in the correct units?**

If a cut is rejected with "falls outside" or carries a "clipped at the boundary" note, take it seriously — it's catching a real geometry mismatch with the part.
