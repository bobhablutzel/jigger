 #! cadette
#
# Copyright 2026 Bob Hablutzel — Apache 2.0
# https://github.com/bobhablutzel/cadette
#
# Fence gate, made up of 2x4s. Accepts width and height
# as parameters, calculates the cross-brace angle and
# puts in place.


define standard/carpentry/fence_gate params width(w), height(h)

    let $stile_w = 1.5in              # 2x4 is really 1.5 x 3.5
    let $rail_w = 1.5in
    let $inner_w = $width - 2 * $stile_w  # opening between stiles
    let $inner_h = $height - 2 * $rail_w   # opening between rails
    let $brace_angle = atan2($inner_h, $inner_w)
    let $upper_corner_angle = atan2($inner_w, $inner_h)
    let $brace_len = hypot($inner_h, $inner_w)

    # Frame
    create part "left_stile" length $height material "lumber-2x4-spf" orient on-end
    rotate "left_stile" 0, -90, 0
    move "left_stile" to 0,0,0

    create part "right_stile" length $height material "lumber-2x4-spf" orient on-end
    rotate "right_stile" 0, -90, 0
    move "right_stile" to ($width - $stile_w), 0, 0

    create part "bottom_rail" length $inner_w material "lumber-2x4-spf" orient flat at $stile_w, 0, 0
    create part "top_rail" length $inner_w material "lumber-2x4-spf" orient flat at $stile_w, $height - $rail_w, 0


    # Diagonal brace (lower-left to upper-right of the opening)
    # Note the asymmetry of the angles on each end; that is so the
    # brace fits nicely between the two corners.
    create part "brace" length $brace_len material "lumber-2x4-spf" orient flat at $stile_w, $rail_w, 0
    rotate "brace" 0, 0, $brace_angle

  cut "brace" miter facing SE angle $upper_corner_angle   # upper end: flush against top rail
  cut "brace" miter facing NE angle $brace_angle   # lower end: flush against left stile                                                                                                                               

    move "brace" to $inner_w + $stile_w, $inner_h + $rail_w,0
end define
