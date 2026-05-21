# CADette

CADette simplifies the design, layout, and construction of cabinets and
woodworking jigs.

At its core, CADette is a purpose-built engine that understands the materials
woodworkers actually use — sheet goods, dimensional lumber, hardwood, granite —
and how those materials join into larger objects. On top of that it provides a
3D view to visualize assemblies, a shop-floor cut sheet to guide construction,
and a command language for creating and sharing reusable designs.

CADette has four building blocks:

- **Parts** — individual pieces, each with a size, material, and placement.
- **Assemblies** — collections of parts connected to each other by joints.
- **Templates** — reusable, parametric instructions for building an assembly.
- **Scripts** — repeatable collections of commands for sharing complex projects.

You manipulate parts and assemblies in the 3D view or, more commonly, through the CADette
command language — typed interactively, or stored in `.cds` script files and
run as a group. The language is documented in full in
[doc/SCRIPTING.md](doc/SCRIPTING.md).

## Status

CADette is in **early alpha** — a friends-and-family preview. Useful caveats:

- **Version is 1.0.0** Yeah, not really. However, the Apple DMG installer requires
  the application to be 1.x.x or greater. This is an alpha release.
- **No save/load yet.** Restarting loses your in-progress work. For now, you can
  capture your commands from the command output window and store them as a .cds file.
  You can run these scripts with the "run" command. This will be addressed soon.
- **Installers are unsigned.** Linux, macOS, and Windows all build, but each
  will show an "unidentified developer" style warning on first launch.
- **Lumber prices are estimates.** Defaults are ballpark — edit
  `~/.cadette/preferences.yaml` to override with your lumberyard's prices.
- **There will be rough edges.** Expect things to fail, or for there to be 
  visual defects.

Please help CADette improve by reporting errors — see [Reporting bugs](#reporting-bugs).

`show about` in the command panel prints the build commit you're on — include
that when reporting issues.

## Install

Download the installer for your platform from the
[Releases page](https://github.com/bobhablutzel/cadette/releases), then follow
the steps below. The installers are unsigned, so each platform will warn on
first launch — the per-platform notes explain how to proceed.

### Linux (.deb)

Install the downloaded package with apt:

```bash
sudo apt install ./cadette_1.0.0_amd64.deb
```

The `./` prefix in front of the filename is required — apt without it treats
the argument as a package name to look up in the repos. A "download performed
unsandboxed as root" warning during install is cosmetic (the `_apt` user can't
read files in your home directory) and the install completes anyway.

Launch from your desktop's app menu (Graphics → Cadette) or:

```bash
/opt/cadette/bin/Cadette
```

jpackage doesn't add the binary to `$PATH` automatically. To run as `cadette`
from any terminal:

```bash
sudo ln -sf /opt/cadette/bin/Cadette /usr/local/bin/cadette
```

To uninstall:

```bash
sudo apt remove cadette
```

### macOS (.dmg)

Open the downloaded `.dmg` and drag Cadette into your Applications folder. The
app is unsigned, so Gatekeeper will block it on first launch — right-click the
app and choose **Open** to bypass the warning (once only).

### Windows (.msi)

Double-click the downloaded `.msi` and follow the installer. The installer is
unsigned, so SmartScreen will warn on first launch — choose **More info → Run
anyway**.

## Quick start

Once CADette is running, type a command into the command panel at the bottom
of the window:

```
create crosscut_sled S w 80cm l 60cm fh 12cm
```

You'll see a 3D model of a table-saw sled and a cut sheet showing how to cut
its parts. See [`examples/`](examples/) for guided walkthroughs of every
bundled template.

## Using CADette

The window has four panels arranged around a central 3D viewport:

- **Parts** (top-left) — a tree of every assembly and part, with dimensions and
  material. Expand assemblies with the ▶/▼ carets.
- **Properties** (left, below Parts) — details of the currently selected
  object.
- **Cut Sheet** (right of the viewport) — how parts pack onto sheet goods and
  lumber boards. Scroll with the mouse wheel; zoom with shift+wheel.
- **Command** (bottom) — type commands here; output scrolls in the area above
  the input field.
- **3D viewport** (center) — orbit with right-click drag, zoom with the wheel.

Drag the splitter bars to resize panels, or drag a panel's tab to rearrange
them. Your layout (splitter positions, active tabs) is saved to
`~/.cadette/preferences.yaml` and restored on the next launch.

**Selecting objects.** Click a part in the 3D view or cut sheet to select its
whole **assembly**; click it again to drill down to that individual **part**.
Shift+click toggles items in a multi-selection. Click empty space to deselect.
Selection is synchronized between the 3D view and the cut sheet.

**Command input.** Press **Enter** to run the typed command. The **Up/Down
arrow keys** recall previous commands; history is persisted across sessions in
`~/.cadette/cmd_history`.

Everything else — creating, moving, rotating, joining, deleting — is done by
typing commands. The full command language is documented in
[doc/SCRIPTING.md](doc/SCRIPTING.md).

## Materials

CADette ships with a catalog of common woodworking materials in both imperial
and metric naming — sheet goods (plywood, MDF, hardboard, baltic birch),
dimensional lumber (2x4 through 2x12, in SPF / pressure-treated / construction
grades), hardwood and softwood boards, aluminum stock, and stone slab (granite,
quartz). Run `show materials` to list them. Materials are open ended and can be 
added through configuration.

The cut-sheet algorithm attempts to pack parts effectively onto sheet goods
based on the size of the sheet. Layout is grain pattern aware. This allows 
the layout manager to ensure that the external grain pattern of the left and
right side of a cabinet align with the grain pattern of the board (so grain
doesn't appear horizontal in the finished product). Parts and templates can be
set to be grain sensitive or not.

For dimensional lumber, the system has to make
a choice: is the design more efficient with one 2x4x16, or two 2x4x8? The 
algorithm attempts to make decisions based on material costs; even though a 
single 16' board theoretically has the same yield, if there are no individual
pieces bigger than 8' 2 8' boards are significantly cheaper. 
Lumber prices live in `~/.cadette/preferences.yaml`; edit them to match your
local supplier for accurate cost estimates.

## Reporting bugs

CADette is alpha software — please report what breaks. Good bug reports are
what makes things better for everyone.

The best channel is **[GitHub Issues](https://github.com/bobhablutzel/cadette/issues)**:
open an issue describing what went wrong. If you don't have a GitHub account
and don't want one, send the same details to me directly at feedback@cadette.app. 

I can't promise that issues will be addressed immediately; this is a passion project
and not a full time job. But I'll try to get back to you as quickly as possible.

Whatever the channel, please include:

- The **version and build commit** — run `show about` in the command panel and
  copy the line it prints.
- **What you did** — the command you ran, or the steps to reproduce.
- **What you expected**, and **what happened instead**.
- A **screenshot** for any visual defect — wrong geometry, rendering glitches,
  garbled layout.
- **How I can contact you** - so I can follow up, or let you know my findings.

## Documentation

- [Scripting reference](doc/SCRIPTING.md) — the complete CADette command
  language.
- [Tutorial: a simple box](doc/TUTORIAL_1_Simple_box.md) — build your first
  assembly from scratch.
- [Examples](examples/) — annotated walkthroughs of the bundled templates
  (cross-cut sled, fence gate, base cabinet, kitchen island).
- [Cut semantics](doc/CUT_SEMANTICS.md) — how cutouts and joinery affect part
  geometry.
- [Scripting pitfalls](doc/SCRIPTING_PITFALLS.md) — common mistakes and how to
  avoid them.

## Key features

- Fully open source and free.
- Lightweight and purpose-built — no distracting, unneeded functionality.
- Rapid, repeatable designs through a command language.
- Reusable, shareable parametric templates.
- 3D visualization.
- Grain-aware cut-sheet layout, updated in real time.
- A comprehensive, extensible catalog of materials and joints.

## Contributing

Contributions are welcome — new templates, joint types, materials, and bug
fixes. See [CONTRIBUTING.md](CONTRIBUTING.md) for how the project is structured
and how to get a change in.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
