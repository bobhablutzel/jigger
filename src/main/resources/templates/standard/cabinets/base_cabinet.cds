#! cadette
#
# Copyright 2026 Bob Hablutzel — Apache 2.0
# https://github.com/bobhablutzel/cadette
#
# Standard face-frameless base cabinet — two sides, bottom panel, top stretcher,
# and a 5.5mm hardboard back. Sides are dadoed to the bottom; top stretcher is
# pocket-screwed; back panel is rabbeted into both sides.

define standard/cabinets/base_cabinet params width(w), height(h), depth(d)
  # Sides
  create part "left-side" size $depth, $height at 0, 0, 0 grain vertical
  rotate "left-side" 0, 90, 0
  create part "right-side" size $depth, $height at $width - $thickness, 0, 0 grain vertical
  rotate "right-side" 0, 90, 0
  # Bottom
  create part "bottom" size $width - 2 * $thickness, $depth at $thickness, 0, 0
  rotate "bottom" -90, 0, 0
  # Top stretcher (capped at 100mm depth)
  create part "top-stretcher" size $width - 2 * $thickness, min($depth, 100mm) at $thickness, $height - $thickness, 0
  rotate "top-stretcher" -90, 0, 0
  # Back panel
  create part "back" material "hardboard-5.5mm" size $width, $height at 0, 0, -$depth
  # Joinery
  join "left-side" to "bottom" with dado
  join "right-side" to "bottom" with dado
  join "left-side" to "top-stretcher" with pocket screws 3
  join "right-side" to "top-stretcher" with pocket screws 3
  join "left-side" to "back" with rabbet
  join "right-side" to "back" with rabbet
end define
