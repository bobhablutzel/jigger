#! cadette
#
# Copyright 2026 Bob Hablutzel — Apache 2.0
# https://github.com/bobhablutzel/cadette
#
# Standard face-frameless base cabinet — two sides, bottom panel, top stretcher,
# and a 5.5mm hardboard back. Sides are dadoed to the bottom; top stretcher is
# pocket-screwed; back panel is rabbeted into both sides.
#
# Optional toe-kick (standard American kitchen profile: 100mm tall × 75mm deep).
# When enabled:
#   - The bottom panel floats up by toe_kick_height so feet can slide under.
#   - The bottom-front corner of each side panel is notched to the toe-kick
#     footprint, creating the classic recessed toe-space.
#   - A toe-kick-front kick plate closes the back of that recess.

define standard/cabinets/base_cabinet params width(w), height(h), depth(d), toe_kick=1, toe_kick_height=100mm, toe_kick_depth=75mm
  # Sides
  create part "left-side" size $depth, $height at 0, 0, 0 grain vertical material "plywood-18mm"
  rotate "left-side" 0, 90, 0
  create part "right-side" size $depth, $height at $width - $thickness, 0, 0 grain vertical material "plywood-18mm"
  rotate "right-side" 0, 90, 0
  # Bottom extends $thickness/2 (= default dado depth) into each side's dado,
  # so the bottom's edges seat in the grooves rather than butting against
  # the inside faces. Floats up by toe_kick_height when toe_kick is enabled.
  create part "bottom" size $width - $thickness, $depth at $thickness / 2, $toe_kick * $toe_kick_height, 0 material "plywood-18mm"
  rotate "bottom" -90, 0, 0
  # Top stretcher butts against the side faces — pocket-screw, no dado, so it
  # sits at the cabinet's interior width like a butt-jointed part.
  create part "top-stretcher" size $width - 2 * $thickness, min($depth, 100mm) at $thickness, $height - $thickness, 0 material "plywood-18mm"
  rotate "top-stretcher" -90, 0, 0
  # Back extends $thickness/2 into each side's rabbet — same logic as the bottom.
  create part "back" material "hardboard-5.5mm" size $width - $thickness, $height at $thickness / 2, 0, -$depth
  # Joinery
  join "left-side" to "bottom" with dado
  join "right-side" to "bottom" with dado
  join "left-side" to "top-stretcher" with pocket_screw screws 3
  join "right-side" to "top-stretcher" with pocket_screw screws 3
  join "left-side" to "back" with rabbet
  join "right-side" to "back" with rabbet
  # Toe-kick geometry — notches + recessed kick plate.
  # Kick-plate Z: parts extend +Z from their origin corner, so to place the
  # plate's *front face* flush with the back of the recess (Z = -toe_kick_depth),
  # the origin corner goes one thickness further back.
  if $toe_kick then
    cut "left-side" rect at 0, 0 size $toe_kick_depth, $toe_kick_height
    cut "right-side" rect at 0, 0 size $toe_kick_depth, $toe_kick_height
    create part "toe-kick-front" size $width, $toe_kick_height at 0, 0, -$toe_kick_depth - $thickness material "plywood-18mm"
  end if
end define
