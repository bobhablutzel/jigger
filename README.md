# CADette

CADette simplifies the design, layout, and construction of cabinets and woodworking jigs.

At it's core, CADette is a purpose-built engine that understands commonly used materials - sheet goods, timbers, etc. - as well as related
materials (granite countertops, e.g) and how those materials can be joined into complex objects. On top of that, CADette provides a 3D
rendering system to visualize the assemblies, a cut-sheet generate to guide the construction process, and a complete command language for
creating and sharing reusable templates and designs.

CADette has three basic building blocks:
- _Parts_, which are individual pieces with an associated size, material, and placement
- _Assemblies_, which are collections of Parts that have been connected to each other (via Joints)
- _Templates_, which are reusable instructions on how to create an Assembly.

These are manipulated either directly in the 3D window, or commonly through the CADette language which
provides powerful commands to manipulate these building blocks. The CADette commands can be stored in 
scripts and executed as a group, or entered manually (often used when playing around, or building an initial
version of a template or script). The CADette language is described fully in [doc/SCRIPTING.md](doc/SCRIPTING.md).

## Status

CADette is in **early alpha** — a friends-and-family preview. Useful caveats:

- **No save/load yet.** Restarting loses your in-progress work. For
  now, save your design as a `.cds` script and re-run it via
  `run path/to/script.cds`.
- **Linux is the primary supported target right now.** The Maven
  build produces a Linux app-image via jpackage; macOS and Windows
  installers are coming.
- **Lumber prices are estimates.** Defaults are ballpark — edit
  `~/.cadette/preferences.yaml` to override with your actual
  lumberyard's prices.

`show about` in the command panel prints the build commit you're on
— include that when reporting issues.

## Quick start

### Install (Linux .deb)

```bash
mvn -P package-app clean package -DskipTests
sudo apt install ./target/dist/cadette_1.0.0_amd64.deb
```

The `./` prefix in front of the filename is required — apt without
it treats the argument as a package name to look up in the repos. A
"download performed unsandboxed as root" warning during install is
cosmetic (the `_apt` user can't read files in your home directory)
and the install completes anyway.

Launch from your desktop's app menu (Graphics → Cadette) or:

```bash
/opt/cadette/bin/Cadette
```

jpackage doesn't add the binary to `$PATH` automatically. To run as
`cadette` from any terminal:

```bash
sudo ln -sf /opt/cadette/bin/Cadette /usr/local/bin/cadette
```

To uninstall:

```bash
sudo apt remove cadette
```

### Building installers on macOS and Windows

The Maven profile auto-detects the host OS and selects the right
installer type. From a checkout of the repo on each platform:

```bash
# macOS (produces target/dist/Cadette.app)
mvn -P package-app clean package -DskipTests
# Then drag target/dist/Cadette.app into /Applications/
# To create the distributable zip for GitHub Releases:
( cd target/dist && zip -r Cadette-1.0.0-macos.zip Cadette.app )

# Windows (produces target\dist\Cadette-1.0.0.msi)
mvn -P package-app clean package -DskipTests
```

Requirements per platform:

- **macOS**: JDK 25 with `jpackage` on PATH. Builds an
  `.app` directory (app-image), not a `.dmg`: jpackage 25.0.2 on
  macOS has a bug where `.dmg` builds drop `--java-options` from the
  inner app's config (which we need for `-XstartOnFirstThread`).
  Distribution is via a zip of the `.app`. Gatekeeper will warn on
  first launch since the app is unsigned — right-click → Open to
  bypass.
- **Windows**: JDK 25 with `jpackage` on PATH; WiX Toolset v3.0+
  (jpackage requires `light.exe`/`candle.exe` for `.msi` generation).
  The produced installer is unsigned and SmartScreen will warn on
  first launch.

These artifacts are unsigned; F&F users should expect a warning the
first time they install. Code signing for both platforms is on the
post-F&F backlog.

### Run from source (development)

```bash
mvn exec:exec
```

### Try an example

In Cadette's command panel at the bottom of the window:

```
create crosscut_sled S w 70cm l 60cm fh 12cm
```

…you'll see a 3D model of a table-saw sled and the cut sheet showing
how to cut the parts. See [`examples/`](examples/) for more
walkthroughs.

## Documentation

- [Examples](examples/) — annotated walkthroughs of the bundled templates (cross-cut sled, fence gate, base cabinet, kitchen island).
- [Tutorial 1: Simple box](doc/TUTORIAL_1_Simple_box.md) — walk through building your first assembly from scratch.
- [Scripting reference](doc/SCRIPTING.md) — full reference for the CADette command language.


** Parts
Parts are the fundamental building block. The CADette engine tracks key aspects of the Part - it's name, size, materials, and so forth
and allows you to manipulate the part via rotating it, moving it, and joining it to other Parts. Some of these aspects are set from
the time of creation - e.g. the name and the material - and require you to delete and recreate the Part to change it. Others, such as the position
and rotation, can be changed at will.

Parts are created with the "create" command, or by right-clicking in an empty area of the scene

Templates allow for variations, such as changing the dimensions, varying the number of elements (such as shelves), and supporting optional elements (such as toe-kicks) so that a single Template can be reused to create a whole family of related Assemblies.


Key CADette features
- Fully open source and free
- Lighweight, purpose built (no distracting unneeded functionality)
- Rapid and repeatable designs with command language
- Reuse and share template designs
- 3D visualization
- Realtime cutsheet layout (grain aware)
- Comprehensive and extensible list of materials and joints

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

78 headless tests validate geometry, templates, joinery, assembly operations, relative positioning, alignment, and cut list generation without requiring a display.

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

CADette ships with 5 standard templates: `base_cabinet`, `wall_cabinet`, `drawer_box`, `shelf_unit`, and `crosscut_sled`. Instantiate them with parameters:

```
create base_cabinet "K1" width 600 height 900 depth 400
create drawer_box "D1" width 500 height 100 depth 400
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

These definitions can be stored in a .cds file and shared or contributed to the project.

Use `show template <name>` to view any built-in template as a copiable `define` block.

### Assembly Operations

Template instances are assemblies — you can operate on them as a unit by name:

```
create base_cabinet "b" width 600 height 900 depth 400
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

### Relative Positioning

Place assemblies and parts relative to each other:

```
create base_cabinet "a" width 600 height 900 depth 400
create base_cabinet "b" width 600 height 900 depth 400 to right of a
create wall_cabinet "wc" width 600 height 400 depth 300 to above a gap 50
move b to left of a
move b to right of a gap 25     — 25mm gap between them
```

Directions: `left`, `right`, `behind`, `in front`, `above`, `below`. The optional `gap` adds spacing between the objects.

### Alignment

Align specific faces of multiple assemblies with a reference:

```
align front of b,c with a       — align the front face of b and c to match a
align back of b with a
align top of b with a
```

Faces: `front`, `back`, `left`, `right`, `top`, `bottom`. This is useful for lining up cabinets of different depths along a wall.

### Cut Sheet Visualization

Cut sheets are rendered in-app as a 2D view alongside the 3D viewport:

```
set layout split            — side-by-side view (3D + cut sheets)
set layout tabs             — toggle between 3D and cut sheet views
export cutsheet pdf          — export to PDF (vector, one page per sheet)
export cutsheet png "cuts.png"  — export as PNG image
export cutsheet jpg          — export as JPEG
export cutlist csv           — export the cut list as CSV (one row per part)
```

Cut sheets update automatically when parts change (dirty-flag lazy recompute). The view scrolls vertically when sheets overflow the viewport.

### Mouse Selection

Click on an object in the 3D viewport or cut sheet view to select it. Selected objects are highlighted with a blue silhouette outline (3D) and blue border (cut sheet). Click empty space to deselect. Selection is synchronized between both views.

Selection uses PowerPoint-style two-level drill-down:
1. **First click** on a part in an assembly selects the **entire assembly** (all parts highlighted)
2. **Second click** on a specific part within the selected assembly drills down to **just that part**
3. Clicking a part in a **different** assembly selects that assembly
4. **Shift+click** adds/removes from the selection (multi-select)

**Right-click** on a part (3D viewport or cut sheet) opens a context menu with:
- **Info** — show the part's properties in the command output
- **Move…** — modal dialog with absolute (x/y/z) or relative (direction + reference + optional gap) placement
- **Rotate…** — modal dialog for x/y/z rotation in degrees
- **Delete** — remove the part

Each action builds the equivalent CADette command and runs it through the executor, so the result is echoed in the command output and participates in undo/redo like any typed command.

Selection clears automatically when the scene changes (creating/deleting parts). Selection info is printed in the command output panel.

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
move "name" to left|right|behind|in front|above|below of "ref" [gap N]
align front|back|left|right|top|bottom of "name"[,...] with "ref"
rotate "name" rx,ry,rz
resize "name" size w,h,d
delete "name"
delete all
set units mm|cm|m|inches|feet
set material "plywood-18mm"
set kerf 3.2            — saw blade kerf width (default 3.2mm / 1/8")
set layout tabs|split   — switch between tabbed and split-pane view
export cutsheet pdf|png|jpg [file]  — export cut sheets
export cutlist csv [file]           — export cut list as CSV
display names           — show name labels on all objects
hide names
run                     — run a .cds script (opens file chooser)
run "path/to/file.cds"
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

Save commands in `.cds` files and run them with the `run` command. A startup script at `~/.cadette/startup.cds` auto-runs on launch.

Scripts can optionally begin with a shebang identifier to mark them as CADette scripts:

```
#! cadette
set units mm
create base_cabinet k1 w 500 h 600 d 400
```

The shebang is a comment to the parser (and therefore a no-op at execution time), but `run` will warn if it finds a shebang that doesn't mention `cadette` — a guardrail against accidentally running a shell or Python script.

Script execution is atomic for undo — a single undo reverts the entire script.

## Materials

CADette includes 24 pre-loaded materials covering common woodworking sheet goods, hardwoods, and metals in both imperial and metric naming:

- Plywood (1/4" through 3/4", 6mm through 18mm)
- MDF, hardboard, melamine
- Hardwood (maple, oak, walnut, cherry)
- Aluminum and steel

Materials automatically switch when you change units (e.g., `plywood-3/4` in inches becomes `plywood-18mm` in metric).

## Architecture

```
src/main/java/app/cadette/
  CADetteApp.java              — Application entry point (jME3 canvas in Swing)
  SceneManager.java           — 3D scene, object tracking, JointRegistry
  CameraController.java       — Camera controls for workshop-scale viewing
  CommandPanel.java           — Swing command input panel
  CutSheetPanel.java          — Swing panel for 2D cut sheet display
  CutSheetRenderer.java       — Shared Graphics2D rendering for cut sheets
  CutSheetExporter.java       — PDF (PDFBox) and image export
  UnitSystem.java             — Unit conversion (mm, cm, m, inches, feet)
  ViewLayoutMode.java         — Tabbed vs split-pane layout enum
  command/
    CADetteCommand.g4          — ANTLR4 grammar for command parsing
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

In `src/main/java/app/cadette/model/JointType.java`, add a new enum constant:

```java
MORTISE_TENON("Mortise & Tenon", true),
```

The two parameters are:
- **displayName** — human-readable name for cut lists and BOM
- **affectsGeometry** — `true` if the joint cuts into the receiving part (like dado/rabbet), `false` if it's purely fastener-based (like butt/pocket screw)

### Step 2: Update the ANTLR Grammar

In `src/main/antlr4/app/cadette/command/CADetteCommand.g4`:

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
- Add test cases in `src/test/java/app/cadette/JoineryTest.java`

### Step 6 (Optional): Add to Templates

If built-in templates should use the new joint type, update their definitions in `TemplateRegistry.java`.

### Design Notes

- **Receiving vs. inserted:** The receiving part gets the machining operation (the groove, mortise, etc.). The inserted part sits in/against it. This distinction drives cut list output.
- **Unit safety:** Depth values in the `Joint` model are always stored in mm internally. The command visitor handles conversion from the user's current unit system.
- **Undo/redo:** `JoinAction` handles undo for all joint types generically via the registry — no per-type undo logic needed.
- **Templates:** Joint commands in templates are plain command strings, so new joint types work in templates automatically once the grammar and visitor support them.

## Roadmap

### Next Up

- Export multiple assembled units as a new reusable template

### Backlog

- Constraint / auto-reflow layout: when an assembly moves (or grows), dependent
  assemblies placed relative to it shift to preserve their relationship, and
  placement commands detect collisions before committing. Likely a persistent
  relationship graph + re-layout pass rather than one-shot coordinate math.

- Drag-to-move for selected parts and assemblies
- Optional 3D background views (floor plane for cabinets, workbench surface, etc.)
- Shelf count logic for the shelf_unit template
- Additional joint types: dovetails, biscuits, dowels, splines
- Wood grain textures and shaders
- Save/load projects
- Collision detection
- Native app packaging (jpackage)

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
