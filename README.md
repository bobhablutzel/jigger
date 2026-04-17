# Jigger

A 3D command-shell CAD tool for designing cabinets, woodworking jigs, and other shop projects. Built on [jMonkeyEngine](https://jmonkeyengine.org/).

Jigger works with real materials — plywood, MDF, hardwood, metal — each with actual thickness. You design assemblies of cut pieces connected by joinery, then generate cut lists and bills of materials.

## Requirements

- Java 21 (OpenJDK)
- Maven 3.8+

## Quick Start

```bash
mvn compile exec:java
```

This opens a 3D viewport with a command panel. Type commands to create parts, assemble them with joinery, and inspect the results.

### Run Tests

```bash
mvn test
```

68 headless tests validate geometry, templates, joinery, assembly operations, and cut list generation without requiring a display.

## Commands

### Creating Parts

```
create part "left side" material "plywood-3/4" size 600,900 at 0,0,0 grain vertical
create part "back panel" material "hardboard-5.5mm" size 800,900
```

Thickness comes from the material. Size is the cut face (width, height). Omit `material` to use the current default. Omit `at` to place at the origin.

### Primitives

```
create box "spacer" size 100,50,30 color red
create sphere "marker" at 200,0,0
create cylinder "dowel" size 8,40
```

### Joinery

```
join "left-side" to "bottom" with dado depth 9
join "left-side" to "back" with rabbet
join "left-side" to "top-stretcher" with pocket screws 3 spacing 150
join "left-side" to "right-side" with butt
```

Joint types: `butt`, `dado`, `rabbet`, `pocket_screw` (or `pocket`).

For dado and rabbet, depth defaults to half the receiving material's thickness if omitted.

### Templates

Jigger ships with 5 built-in templates: `base-cabinet`, `wall-cabinet`, `drawer-box`, `shelf-unit`, and `crosscut-sled`. Instantiate them with parameters:

```
create base-cabinet "K1" width 600 height 900 depth 400
create drawer-box "D1" width 500 height 100 depth 400
```

Define your own templates with parametric variables and arithmetic:

```
define "my-box" params width(w), height(h), depth(d)
  create part "left" size $d,$h grain vertical
  create part "right" size $d,$h grain vertical
  create part "bottom" size $w,$d
  move "right" to $w - $thickness, 0, 0
  join "left" to "bottom" with dado
  join "right" to "bottom" with dado
end define
```

Use `show template <name>` to view any built-in template as a copiable `define` block.

### Assembly Operations

Template instances are assemblies — you can operate on them as a unit by name:

```
create base-cabinet "b" width 600 height 900 depth 400
move b to 100,0,0           — moves all parts as a unit
rotate b 0,90,0             — rotates entire assembly around its origin
delete b                    — deletes all parts in the assembly
display name b              — show labels on all parts
show info b                 — assembly details: template, parts, joints
```

Name resolution checks assemblies first, then individual parts. Use the `assembly/part` notation to target a specific part within an assembly:

```
move "b/left-side" to 0,0,0     — move just the left side
show info "b/left-side"          — info on a single part
```

The `list` command groups parts by assembly.

### Cut Sheet Visualization

Cut sheets are rendered in-app as a 2D view alongside the 3D viewport:

```
set layout split            — side-by-side view (3D + cut sheets)
set layout tabs             — toggle between 3D and cut sheet views
export cutsheet pdf          — export to PDF (vector, one page per sheet)
export cutsheet png "cuts.png"  — export as PNG image
export cutsheet jpg          — export as JPEG
```

Cut sheets update automatically when parts change (dirty-flag lazy recompute).

### Inspection

```
list                    — list all objects in the scene
show objects            — display all objects and positions
show materials          — list available materials
show joints             — list all joints
show cutlist            — cut list grouped by material with machining operations
show bom               — bill of materials: actual sheet counts, waste %, fasteners
show layout            — sheet layout optimization with part placements
show units              — display current unit setting
show templates          — list available templates
```

### Other Commands

```
move "name" to x,y,z
rotate "name" rx,ry,rz
resize "name" size w,h,d
delete "name"
delete all
set units mm|cm|m|inches|feet
set material "plywood-18mm"
set kerf 3.2            — saw blade kerf width (default 3.2mm / 1/8")
set layout tabs|split   — switch between tabbed and split-pane view
export cutsheet pdf|png|jpg [file]  — export cut sheets
display names           — show name labels on all objects
hide names
run                     — run a .jigs script (opens file chooser)
run "path/to/file.jigs"
undo / redo
help
exit
```

### Keyboard Shortcuts

| Key | Action |
|-----|--------|
| R | Run script |
| L | List objects |
| Q | Quit |
| Escape | Toggle focus between command panel and viewport |
| Ctrl+Z | Undo |
| Ctrl+Shift+Z | Redo |

### Scripts

Save commands in `.jigs` files and run them with the `run` command. A startup script at `~/.jigger/startup.jigs` auto-runs on launch.

Script execution is atomic for undo — a single undo reverts the entire script.

## Materials

Jigger includes 24 pre-loaded materials covering common woodworking sheet goods, hardwoods, and metals in both imperial and metric naming:

- Plywood (1/4" through 3/4", 6mm through 18mm)
- MDF, hardboard, melamine
- Hardwood (maple, oak, walnut, cherry)
- Aluminum and steel

Materials automatically switch when you change units (e.g., `plywood-3/4` in inches becomes `plywood-18mm` in metric).

## Architecture

```
src/main/java/com/jigger/
  JiggerApp.java              — Application entry point (jME3 canvas in Swing)
  SceneManager.java           — 3D scene, object tracking, JointRegistry
  CameraController.java       — Camera controls for workshop-scale viewing
  CommandPanel.java           — Swing command input panel
  CutSheetPanel.java          — Swing panel for 2D cut sheet display
  CutSheetRenderer.java       — Shared Graphics2D rendering for cut sheets
  CutSheetExporter.java       — PDF (PDFBox) and image export
  UnitSystem.java             — Unit conversion (mm, cm, m, inches, feet)
  ViewLayoutMode.java         — Tabbed vs split-pane layout enum
  command/
    JiggerCommand.g4          — ANTLR4 grammar for command parsing
    CommandExecutor.java      — Command dispatch (pre-parse intercepts + ANTLR)
    CommandVisitor.java       — ANTLR visitor implementing all commands
    *Action.java              — Undoable actions (move, rotate, delete, join, etc.)
    *AssemblyAction.java      — Assembly-level undoable actions
  model/
    Assembly.java             — Named collection of parts (template instance)
    Joint.java                — Joint data class (receiver, inserted, type, depth)
    JointType.java            — Enum of joint types with metadata
    JointRegistry.java        — Central store for all joints in scene
    MaterialCatalog.java      — 24 pre-loaded materials
    Material.java             — Material with thickness, color, grain rules
    CutListGenerator.java     — Cut list and BOM generation
    SheetLayout.java          — Data model for sheet layout results
    SheetLayoutGenerator.java — Groups parts by material, runs bin packing
    GuillotinePacker.java     — Guillotine bin packing algorithm
    TemplateRegistry.java     — Built-in and user-defined templates
    ExpressionEvaluator.java  — Arithmetic for template variables
```

The ANTLR grammar defines the command language. `CommandVisitor` implements each command via the visitor pattern. Complex commands (`define`, `run`, `stats`, `template create`) are intercepted before parsing in `CommandExecutor`.

Each 3D object uses a wrapper `Node` at the corner position containing a `Geometry` offset by half-size, so rotation pivots around the object's corner.

Internal units are always millimeters. Display and input are converted through `UnitSystem`.

## Contributing New Joint Types

The joinery system is designed so that adding a new joint type (e.g., mortise & tenon, dovetail, biscuit) follows a consistent pattern across a small number of files.

### Step 1: Add the Joint Type Enum Value

In `src/main/java/com/jigger/model/JointType.java`, add a new enum constant:

```java
MORTISE_TENON("Mortise & Tenon", true),
```

The two parameters are:
- **displayName** — human-readable name for cut lists and BOM
- **affectsGeometry** — `true` if the joint cuts into the receiving part (like dado/rabbet), `false` if it's purely fastener-based (like butt/pocket screw)

### Step 2: Update the ANTLR Grammar

In `src/main/antlr4/com/jigger/command/JiggerCommand.g4`:

1. Add a lexer token:
   ```antlr
   MORTISE_TENON_JT : 'mortise-tenon' | 'mortise_tenon' | 'mortise' ;
   ```

2. Add it to the `jointType` parser rule:
   ```antlr
   jointType
       : BUTT_JT
       | DADO_JT
       | RABBET_JT
       | POCKET_SCREW_JT
       | MORTISE_TENON_JT
       ;
   ```

Support flexible input formats (hyphens, underscores, short aliases) for a good command-line experience.

### Step 3: Handle It in the Command Visitor

In `CommandVisitor.java`, in `visitJoinCommand()`, add parsing for the new token:

```java
else if (jtCtx.MORTISE_TENON_JT() != null) type = JointType.MORTISE_TENON;
```

If your joint type has geometry effects (`affectsGeometry = true`), the existing depth validation logic applies automatically — smart defaults (half material thickness), capping at material thickness, and thin-material warnings.

If your joint needs additional parameters beyond what the `Joint` model currently holds (depth, screw count, spacing), you'll need to extend `Joint.java` with new fields.

### Step 4: Update Cut List Generation

In `CutListGenerator.java`, add a case to `generateCutList()` for the machining operation text:

```java
case MORTISE_TENON -> operations.add(String.format(
    "mortise %.1fmm deep for \"%s\"", j.getDepthMm(), j.getInsertedPartName()));
```

If your joint uses fasteners, update `generateFasteners()` as well.

### Step 5: Update Help and Tests

- Update `helpText()` in `CommandVisitor.java` to list the new type
- Add test cases in `src/test/java/com/jigger/JoineryTest.java`

### Step 6 (Optional): Add to Templates

If built-in templates should use the new joint type, update their definitions in `TemplateRegistry.java`.

### Design Notes

- **Receiving vs. inserted:** The receiving part gets the machining operation (the groove, mortise, etc.). The inserted part sits in/against it. This distinction drives cut list output.
- **Unit safety:** Depth values in the `Joint` model are always stored in mm internally. The command visitor handles conversion from the user's current unit system.
- **Undo/redo:** `JoinAction` handles undo for all joint types generically via the registry — no per-type undo logic needed.
- **Templates:** Joint commands in templates are plain command strings, so new joint types work in templates automatically once the grammar and visitor support them.

## Roadmap

### Next Up

- Relative positioning between assemblies (`create base-cabinet b to left of "a"`)
- Export multiple assembled units as a new reusable template
- Right-click popup menu in 3D view for create/move/delete on assemblies

### Backlog

- Persistent command history across sessions
- Mouse selection and drag-to-move for parts and assemblies
- Optional 3D background views (floor plane for cabinets, workbench surface, etc.)
- Shelf count logic for the shelf-unit template
- Additional joint types: dovetails, biscuits, dowels, splines
- Wood grain textures and shaders
- Save/load projects
- Export cut lists to CSV
- Collision detection

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
