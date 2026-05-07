#! cadette
#
# Copyright 2026 Bob Hablutzel — Apache 2.0
# https://github.com/bobhablutzel/cadette
#
# Table-saw crosscut sled — MDF base with front and rear fences butt-joined
# to it. `fence_height` controls the fence thickness along the blade path.

define standard/jigs/crosscut_sled params width(w), length(l), fence_height(fh)

  # Create a base that rests on the table saw from baltic birch plywood
  create part "base" size $width, $length at 0, 0, 0 material "baltic-birch-18mm"
  rotate "base" 90, 0, 0

  # Now create the handle. We're going to laminate two pieces of 
  # plywood together to create a more structurally sound handle
  # We create the two here, and glue them up later.

  for $i = 0 to 1
    create part "handle$i" size $width, $fence_height at 0,0,($length * 4 / 5)-(18mm*$i) 
           material "baltic-birch-18mm"

    # Left of handle cutoff
    cut "handle$i" rect at 0,($fence_height-6cm) size ($width-46.5cm),6cm
    cut "handle$i" circle at ($width-46.5cm), ($fence_height-4.5cm) radius 1.5cm
    cut "handle$i" spline ($width-46.45cm,$fence_height-3cm), 
                          ($width-45cm, $fence_height-2.5cm),
                          ($width-44.9cm,$fence_height-2cm), 
                          ($width-44.5cm,$fence_height-1cm), 
                          ($width-44cm,$fence_height-0.5cm), 
                          ($width-43cm,$fence_height-0.1cm), 
                          ($width-41cm,$fence_height), 
                          ($width-46.5cm,$fence_height)
    cut "handle$i" rect at 0,($fence_height-4.5cm) size ($width-45cm),4.5cm

    # Right of handle cutoff
    cut "handle$i" rect at ($width-13.5cm),($fence_height-6cm) size 13.5cm,6cm
    cut "handle$i" circle at ($width-13.5cm), ($fence_height-4.5cm) radius 1.5cm
    cut "handle$i" spline ($width-15cm,$fence_height-3cm), 
                          ($width-15.1cm,$fence_height-2cm), 
                          ($width-15.5cm,$fence_height-1cm), 
                          ($width-16cm,$fence_height-0.5cm), 
                          ($width-17cm,$fence_height-0.1cm), 
                          ($width-19cm,$fence_height), 
                          ($width-15cm,$fence_height)
    cut "handle$i" rect at $width-15cm,$fence_height-4.5cm size 15cm,4.5cm

    if ($i > 0) then
      join "handle$i" to "handle${i-1}" with glue
    end if

    join "handle$i" to "base" with countersunk_screw screws 4 spacing 200

  end for

  create part "slotRunner" size $length+10cm, 9mm material "baltic-birch-18mm"
  rotate "slotRunner" 180,-90,0
  move "slotRunner" to $width-50cm,-18mm,-5cm
  join "slotRunner" to "base" with countersunk_screw screws 4 spacing 200

end define
