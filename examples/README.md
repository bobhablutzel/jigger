# Cadette Examples

Real-world projects that show what Cadette can do. Each subdirectory
covers one example with a `README.md` explaining the command(s), the
parameters you can tweak, and what to look at in the result.

Run any command in the Cadette command panel at the bottom of the
window. Type `help` for the full command reference, or the example's
README for a guided walkthrough.

## Available examples

| Example | One-liner | What it shows |
|---------|-----------|---------------|
| [Cross-cut sled](cross-cut-sled/) | `create crosscut_sled S w 70cm l 60cm fh 12cm` | Single-command template, spline-curved handle, fence + base composition |
| [Fence gate](fence-gate/) | `create fence_gate G w 100cm h 180cm` | Dimensional lumber (2x4s), diagonal brace, mitered cuts, dollar-cost lumber packing |
| [Base cabinet](base-cabinet/) | `create base_cabinet K w 50cm h 60cm d 40cm` | Cabinet template with side dadoes, back rabbet, optional toe-kick — the foundation for kitchen islands |
| [Island](island/) | `run examples/island/island.cds` | Multi-template composition: two cabinets + counter |

## After running an example

Things worth trying with any of them:

- **3D view** (top-right): orbit with right-click drag, zoom with the
  mouse wheel.
- **Parts list** (top-left): every part the template generated, with
  its dimensions and material.
- **Cut sheet** (bottom-right): how the parts pack onto plywood
  sheets / lumber boards. Wheel to scroll, shift+wheel to zoom.
- **Bill of materials**: `show bom`.
- **Export**: `export cutsheet pdf my-project.pdf` to get a
  printable shop-floor artifact.

## Tweaking the examples

Templates expose parameters. Every example's README lists the
parameters and what they do. To see all available templates, run
`show templates`. To see one template's full source, `show template
standard/jigs/crosscut_sled` (or the path you want).

## Writing your own

Cadette projects are scripts. The `island.cds` example shows the
composition pattern — instantiate templates, attach parts, run
operations. Save your work to a `.cds` file and re-run it with `run
my-project.cds`.
