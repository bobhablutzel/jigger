# CADette Scripting Reference

This is the complete reference for the CADette command language.

Commands can be entered one at a time in the **Command** panel, or saved in a
`.cds` script file and run as a group with the `run` command. The two are the
same language — interactive use is good for experimenting and roughing out a
design; scripts are good for anything you want to keep, share, or re-run.

> **No save/load yet.** Until project save/load lands, a `.cds` script *is*
> your saved project. Build your design as a script and re-run it with `run`.

## Contents

- [Scripts and comments](#scripts-and-comments)
- [Units and dimensions](#units-and-dimensions)
- [Expressions and variables](#expressions-and-variables)
- [Creating parts](#creating-parts)
- [Primitives](#primitives)
- [Placing and moving](#placing-and-moving)
- [Relative placement](#relative-placement)
- [Alignment](#alignment)
- [Rotating and orienting](#rotating-and-orienting)
- [Resizing](#resizing)
- [Joinery](#joinery)
- [Cutouts: cut, keep, fillet](#cutouts-cut-keep-fillet)
- [Named shapes](#named-shapes)
- [Templates](#templates)
- [Messages](#messages)
- [Inspection](#inspection)
- [Export](#export)
- [Settings](#settings)
- [Undo and redo](#undo-and-redo)
- [Command quick reference](#command-quick-reference)

## Scripts and comments

A script is a sequence of commands, one per line. Run one with:

```
run path/to/script.cds
```

A path is currently required — give `run` an explicit script path.

Scripts may begin with a [shebang](https://en.wikipedia.org/wiki/Shebang_\(Unix\)) line identifying them as CADette scripts:

```
#! cadette
```

The shebang is a comment to the parser (a no-op at execution time), but `run`
will warn if it finds a shebang that doesn't mention `cadette` — a guardrail
against accidentally running a shell or Python script.

Comments start with `#` or `//` and run to the end of the line. Blank lines are
ignored.

Script execution is **atomic for undo** — a single `undo` reverts an entire
script, not just its last command.

A startup script at `~/.cadette/startup.cds` runs automatically on launch, if
it exists — a good place for `set units`, `set theme`, and `using` defaults.

## Units and dimensions

CADette works in whatever unit you set; internally everything is millimeters.

```
set units mm | cm | m | inches | feet
show units                — display the current unit
```

Bare numbers are interpreted in the current unit. You can also write a
**dimensional literal** with an explicit unit suffix, which is independent of
the current setting:

```
100mm     5cm     2.5in     0.5m     8ft
```

A dimensional literal binds tighter than any arithmetic operator, so
`100mm + 5cm` parses as `(100mm) + (5cm)` and evaluates correctly regardless of
the current units. Use literals in templates and shared scripts so they behave
the same for everyone.

## Expressions and variables

Anywhere a number is expected, you can write an expression.

**Operators**, tightest-binding first: unary `-`, `not`; `*` `/`; `+` `-`;
comparisons `<` `<=` `>` `>=`; equality `==` `!=`; `and`; `or`. Parenthesize
with `( )`. Comparisons and logical operators yield `1` (true) or `0` (false).

**Functions**:

- `min(a, b, ...)`, `max(a, b, ...)` — variadic (i.e. they accept any number of values).
- `sin cos tan asin acos atan atan2 sqrt hypot abs pow floor ceil round
  log exp` — standard math. Trigonometric functions take **degrees**
  (consistent with `rotate`). Use `radians(d)` / `degrees(r)` to convert.
- The constants `pi` and `e` are available as bare names.

**Variables** are bound with `let` and referenced with a leading `$`:

```
let $clearance = 3mm
let $shelf_w = $cabinet_w - 2 * $thickness
```

Variables are mutable — the last assignment wins. At the top level they live in
a global scope; inside a template or `for` loop they are scoped to that body
and disappear when it ends.

**String interpolation**: inside a quoted string, `${expr}` is replaced with
the expression's value. This is how scripts build distinct names in a loop:

```
create part "shelf${i}" size $w, $depth
```

## Creating parts

A part is a single piece of material.

```
create part <name> [material <m>] [size <w>,<h> | length <l>]
                    [orient flat|on-edge|on-end] [at <x>,<y>,<z>]
                    [grain vertical|horizontal|any]
```

- `size` gives the **cut face** as width, height. Thickness comes from the
  material — you never specify it directly.
- `length` is the dimensional-lumber form: give only a length and the width
  (and thickness) are taken from the lumber material's profile.
- `orient` sets an initial shop-talk orientation for lumber (see
  [Rotating and orienting](#rotating-and-orienting)).
- `at` places the part's origin corner; omit it to place at the origin.
- `grain` sets the grain direction for cut-sheet packing; omit it for `any`.
- Omit `material` to use the current default (`set material`).

```
create part "left side" material "plywood-18mm" size 60cm, 90cm grain vertical
create part "rail" material "lumber-2x4-spf" length 100cm
```

Clauses may appear in any order. A part's name and material are fixed at
creation; position, rotation, and size can change afterward.

## Primitives

Simple geometric solids, useful as spacers, markers, and reference objects:

```
create box <name> size <w>,<h>,<d> [at <x>,<y>,<z>] [color <c>]
create sphere <name> [size <d>] [at ...] [color <c>]
create cylinder <name> [size ...] [at ...] [color <c>]
```

`size` accepts a single value (uniform) or three (w, h, d). Colors are `red`,
`green`, `blue`, `yellow`, `white`, or a hex literal like `#3a7fd5`.

Geometric solids do not have a material associated with them and therefore do not 
show up on cut sheets.

## Placing and moving

```
move <name> to <x>,<y>,<z>
```

Coordinates are in the current units. A part's **origin corner** sits at the
given point; the part extends in +X, +Y, +Z from there. (This corner-origin
model is why rotation pivots around the corner, not the center.)

## Relative placement

Place an object relative to another, instead of with absolute coordinates:

```
move <name> [to] <direction> of <reference> [gap <n>]
```

Directions: `left`, `right`, `behind`, `in front`, `above`, `below`. The
optional `gap` inserts spacing between the two objects.

Relative placement also works as a clause when creating a template instance
(see [Templates](#templates)):

```
create base_cabinet b2 w 60cm h 90cm d 40cm   right of b1 gap 0
move shelf to above base gap 2cm
```

## Alignment

Align one face of one or more objects with a reference object:

```
align <face> of <name>[,<name>...] with <reference>
```

Faces: `front`, `back`, `left`, `right`, `top`, `bottom`. Useful for lining up
cabinets of different depths along a wall:

```
align back of cab2, cab3 with cab1
```

## Rotating and orienting

Rotations are in **degrees**, given as X, Y, Z.

```
rotate <name> <rx>,<ry>,<rz>           — relative (composes onto current)
rotate <name> by <rx>,<ry>,<rz>        — relative (explicit form)
rotate <name> to <rx>,<ry>,<rz>        — absolute (replaces current)
```

Bare `rotate` is **relative** — it adds to the object's current orientation,
matching how you iterate ("now another quarter turn"). Use `to` when you want
to set an absolute orientation.

For dimensional lumber, `orient` is a named shortcut that sets a shop-talk
stance (a goal state, not a delta):

```
orient <name> flat | on-edge | on-end
```

## Resizing

```
resize <name> size <w>,<h>,<d>
resize <name> width <w> height <h> depth <d>
```

The component form takes any subset, in any order.

## Joinery

Connect two parts with a joint:

```
join <receiver> to <inserted> with <joint-type> [depth <n>]
     [screws <n>] [spacing <n>]
```

The **receiver** (first part) gets the machining operation — the groove,
rabbet, or pocket holes. The **inserted** part (second) seats into or against
it. This distinction drives cut-list output. Items like screws will be 
reflected in the bill of materials (BOM).

Joint types:

| Type | Aliases | Notes |
|------|---------|-------|
| `butt` | | No machining; parts simply abut. |
| `dado` | | Groove in the receiver. `depth` defaults to half the receiver's thickness. |
| `rabbet` | | Step cut in the receiver's edge. `depth` defaults to half thickness. |
| `pocket_screw` | `pocket` | Angled pocket-hole screws. Use `screws` / `spacing`. |
| `countersunk_screw` | | Perpendicular flush-head screws. Use `screws` / `spacing`. |
| `glue` | | Logical glue bond — no machining, no fasteners (laminations). |

```
join "left-side" to "bottom" with dado
join "left-side" to "back" with rabbet depth 9mm
join "left-side" to "stretcher" with pocket_screw screws 3
join "layer1" to "layer2" with glue
```

Use `validate` to geometrically sanity-check joints (see
[Inspection](#inspection)).

## Cutouts: cut, keep, fillet

`cut` removes a region from a part; `keep` does the inverse, removing
everything *outside* the named region (handy for tracing an irregular outline).

```
cut  <part> <shape>
keep <part> <shape>
```

Shapes and their clauses:

| Shape | Form |
|-------|------|
| `rect` | `rect at <x>,<y> size <w>,<h>` |
| `circle` | `circle at <x>,<y> radius <r>` |
| `polygon` | `polygon (x,y), (x,y), ...` (vertex list) |
| `spline` | `spline (x,y), (x,y), ...` (smooth curve through points) |
| named shape | `shape <name> at <x>,<y>` (see [Named shapes](#named-shapes)) |
| `miter` | `miter facing <dir> angle <degrees>` |

All shapes accept two optional clauses:

- `depth <n>` — a partial-depth cutout. Omit it for a through cut.
- `face front | back` — which face the cut enters from. Defaults to the front.

```
cut "side" rect at 0,0 size 75mm,100mm                   — toe-kick notch
cut "door" circle at 35mm,35mm radius 17.5mm depth 12mm   — hinge cup hole
cut "handle" rect at 30mm,40mm size 120mm,25mm depth 8mm face back
```

`fillet` rounds an outer corner with a quarter-arc — sugar over a small polygon
cut:

```
fillet <part> at <x>,<y> radius <r> facing <NE|NW|SE|SW> [depth <n>]
```

`facing` names the quadrant the corner opens *into* (the empty side).

## Named shapes

Declare a reusable shape once, then cut it into parts wherever you need it:

```
shape <name> polygon (x,y), (x,y), ...
shape <name> spline  (x,y), (x,y), ...
```

A shape's vertices are stored relative to its own origin. Use it with the
`shape` cut form, which translates it by the anchor point:

```
shape notch polygon (0,0), (40mm,0), (40mm,20mm), (0,20mm)
cut "rail-a" shape notch at 100mm,0
cut "rail-b" shape notch at 250mm,0
```

## Templates

A template is a parametric recipe for an assembly. Instantiate one with
`create`:

```
create <template> <name> [<param> <value> ...] [at <x>,<y>,<z>]
                          [<relative placement>]
```

```
create base_cabinet K width 60cm height 90cm depth 40cm
create base_cabinet K w 60cm h 90cm d 40cm        — shorthand parameter names
```

The template instance is an **assembly**. Operate on it as a unit by name
(`move K to ...`, `rotate K ...`, `delete K`), or address one part inside it
with `assembly/part` notation (`move "K/left-side" to ...`).

### Defining templates

```
define <template-name> [params <decl>, <decl>, ...]
  ...commands...
end define
```
Note that the template name specifies a path which matches where the
template resides in the repository. See the standard shelf_unit template
definition below.

Each parameter declaration is `name`, optionally with a shorthand alias in
parentheses and a default value:

```
define my_box params width(w), height(h), depth(d), toe_kick = 1
```

A later default may reference an earlier parameter. Inside the body, refer to
parameters as `$width`, `$w`, etc. The variable `$thickness` is also available
— it is the current material's thickness.

**Control flow** — `if` and `for` blocks — works inside a `define` body, at
the top level of a `.cds` script, and interactively in the command panel.
(`define` itself is top-level only: you can't define a template inside a loop
or conditional.)

```
if <expression> then
  ...commands...
else
  ...commands...
end if

for $i = <start> to <end>           — both bounds inclusive
  ...commands...
end for
```

```
define standard/cabinets/shelf_unit params width(w), height(h), depth(d), shelves(n)
  create part "left"  size $depth, $height grain vertical
  create part "right" size $depth, $height grain vertical
  for $i = 1 to $shelves
    create part "shelf${i}" size $width, $depth
    move "shelf${i}" to 0, $i * $height / ($shelves + 1), 0
  end for
end define
```

Templates can be stored in `.cds` files and shared or contributed to the
project. `show template <name>` prints any template as a copiable `define`
block.

### Resolving template names

Templates live under namespaced paths (e.g. `standard/cabinets/base_cabinet`).
`using` lets you refer to them by bare name:

```
using standard/cabinets       — bare `base_cabinet` now resolves here
using none                    — clear the namespace scoping
which base_cabinet            — show the fully-qualified name + source file
```

A `using` inside a `run`-invoked script is undone when that script finishes; a
`using` in the startup script persists.

## Messages

Scripts and templates can write to the command output:

```
print "informational note"
warn  "something to be careful about"
error "a serious problem"
```

The three differ only in the prefix on the rendered line — none of them halt
the script. Strings support `${expr}` interpolation:

```
warn "Sled width ${$width} is below the 80cm safe minimum"
```

## Inspection

```
list                      — every object in the scene, grouped by assembly
show objects              — objects with their positions
show info <name>          — full detail for one object, part, or assembly
show materials            — the material catalog
show templates            — available templates
show joints               — every joint in the scene
show cutlist              — cut list grouped by material, with machining ops
show bom                  — bill of materials: sheet/board counts, waste, fasteners
show layout               — sheet-layout optimization with part placements
show units                — current unit setting
show theme | show themes  — current theme / available themes
show about                — build version and commit
show template <name>      — print a template as a define block
which <name>              — resolved template name and source file
stats                     — scene statistics
validate [<assembly>]     — geometric joint check; all joints, or one assembly
```

`validate` reports per-joint issues without changing the scene. Run it before
exporting a cut sheet.

Name labels in the 3D view:

```
display names             — label every object
display name <name>       — label one
hide names | hide name <name>
```

## Export

```
export cutlist pdf  [filename]      — vector PDF, one page per sheet
export cutlist png  [filename]      — raster image
export cutlist jpeg [filename]      — raster image
export cutlist csv  [filename]      — one row per part
```

With no filename, the user will be prompted for the file name. The cut sheet is also visible live in the
Cut Sheet panel and updates automatically as parts change.

## Settings

```
set units <unit>                       — mm, cm, m, inches, feet
set material <name>                     — default material for new parts
set kerf <n>                            — saw blade kerf width (default 3.2mm)
set theme <name>                        — UI theme (see `show themes`)
set script_path "<dir>"[, "<dir>"...]   — prepend directories to the script search path
set script_path none                    — clear the user script path
```

The script search path always falls back to `~/.cadette/scripts/` and
`./scripts/` after any user-configured entries.

## Undo and redo

```
undo
redo
```

Every command is undoable. A `run` of a script undoes as a single unit.

## Command quick reference

| Command | Purpose |
|---------|---------|
| `create part …` | Create a part |
| `create box\|sphere\|cylinder …` | Create a primitive |
| `create <template> <name> …` | Instantiate a template |
| `move <name> to …` / `move <name> <dir> of <ref>` | Position absolutely / relatively |
| `align <face> of … with <ref>` | Align faces |
| `rotate <name> [by\|to] …` | Rotate (relative / absolute) |
| `orient <name> flat\|on-edge\|on-end` | Named lumber orientation |
| `resize <name> …` | Change a part's size |
| `join <a> to <b> with <type> …` | Create a joint |
| `cut` / `keep` / `fillet` | Cutouts |
| `shape <name> …` | Declare a named shape |
| `define … end define` | Define a template |
| `using` / `which` | Scope / resolve template names |
| `let $name = <expr>` | Bind a variable |
| `print` / `warn` / `error` | Emit a message |
| `if … then … end if`, `for … end for` | Control flow (templates, scripts, REPL) |
| `list`, `show …`, `validate`, `stats` | Inspection |
| `display` / `hide` `name[s]` | Toggle name labels |
| `export cutlist <format>` | Export the cut sheet / cut list |
| `set …` | Units, material, kerf, theme, script path |
| `delete <name>` / `delete all` | Remove objects |
| `undo` / `redo` | Undo history |
| `run <path>` | Run a script |
| `help` | In-app command help |
| `exit` | Quit |
