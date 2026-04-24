#! cadette
# Open shelving unit with an integer number of evenly-spaced, permanently-
# fixed shelves between the top and bottom panels. Shelves sit in dados cut
# into the side panels — once assembled, they don't move. (An
# adjustable_shelf_unit using shelf pins is a future variant; that needs
# partial-depth cuts, which requires part cutouts — see Phase E.)
#
# Parts are created as flat rectangles in the X-Y plane and rotated into
# position — sides stand up (rotate around Y), tops/bottoms/shelves lie
# flat (rotate around X). Same convention as shelf_unit and base_cabinet.
define standard/cabinets/fixed_shelf_unit params width(w), height(h), depth(d), shelf_count(s)=3
  create part "left-side" size $depth, $height at 0, 0, 0 grain vertical
  rotate "left-side" 0, 90, 0
  create part "right-side" size $depth, $height at $width - $thickness, 0, 0 grain vertical
  rotate "right-side" 0, 90, 0
  create part "bottom" size $width - 2 * $thickness, $depth at $thickness, 0, 0
  rotate "bottom" -90, 0, 0
  create part "top" size $width - 2 * $thickness, $depth at $thickness, $height - $thickness, 0
  rotate "top" -90, 0, 0
  create part "back" material "hardboard-5.5mm" size $width, $height at 0, 0, -$depth
  # Shelves evenly spaced — $i/($shelf_count+1) places them between the
  # bottom and top panels without touching either. 10mm back-offset keeps
  # them clear of the hardboard back.
  for $i = 1 to $shelf_count
    create part "shelf_$i" size $width - 2 * $thickness, $depth - 10mm at $thickness, $i * $height / ($shelf_count + 1), 0
    rotate "shelf_$i" -90, 0, 0
  end for
  # Joinery — sides receive everything via dados; back sits in rabbets.
  join "left-side" to "top" with dado
  join "right-side" to "top" with dado
  join "left-side" to "bottom" with dado
  join "right-side" to "bottom" with dado
  join "left-side" to "back" with rabbet
  join "right-side" to "back" with rabbet
  for $i = 1 to $shelf_count
    join "left-side" to "shelf_$i" with dado
    join "right-side" to "shelf_$i" with dado
  end for
end define
