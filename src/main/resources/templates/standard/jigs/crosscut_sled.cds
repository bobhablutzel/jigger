#! cadette
#
# Copyright 2026 Bob Hablutzel — Apache 2.0
# https://github.com/bobhablutzel/cadette
#
# Table-saw crosscut sled — MDF base with front and rear fences butt-joined
# to it. `fence_height` controls the fence thickness along the blade path.

define standard/jigs/crosscut_sled params width(w), length(l), fence_height(fh), stop_block_left(sbl) = 0, stop_block_right(sbr) = 1, handle_thickness = 2

  # Check the width - should be at least 80cm
  if ($width < 80cm) then
    warn "Width of the sled may not be enough to accomodate safe handle"
  end if

  # Check the fence height - should be at least 12cm to safely allow the
  # blade to travel through without completely separating the jib
  if ($fence_height < 12cm) then 
    warn "Height of fence might not be enough for blade clearance"
  end if

  # Create a base that rests on the table saw from baltic birch plywood
  create part "base" size $width, $length at 0, 0, 0 material "baltic-birch-18mm"
  rotate "base" 90, 0, 0

  # Now create the handle. We're going to laminate multiple pieces of 
  # plywood together to create a more structurally sound handle (default two).
  # We create them here, and perform all the identical operations (profiling, 
  # gluing, etc.). There will still be the need for creating stop-block t-slot
  # channels; that happens separately below.
  for $i = 1 to $handle_thickness
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

    # If this isn't the first handle part, then denote that we glue
    # it to the previous one
    if ($i > 1) then
      join "handle$i" to "handle${i-1}" with glue
    end if

    # All the handles are additionally joined to the base via countersunk screws
    join "handle$i" to "base" with countersunk_screw screws 4 spacing 200

  end for



  # This code creates the slot runner - designed to fit within a standard
  # 3/4" (18mm) table saw slot. The user can choose to use something else for
  # the slot runner - e.g. a piece of extruded aluminum - but this gives the 
  # definition of where it should be. The runner is positioned so that the 
  # saw blade should be within the raised part of the handle, so that when the 
  # zero-clearance cut is made, there should still be plenty of material to 
  # keep the sled in one piece.
  create part "slotRunner" size ($length+10cm), 9mm material "baltic-birch-18mm"
  rotate "slotRunner" 180,-90,0
  move "slotRunner" to $width-50cm,-18mm,-5cm
  join "slotRunner" to "base" with countersunk_screw screws 4 spacing 200


  # See if we are adding a stop block on the right
  if $stop_block_right then

    # The stop block is created as two pieces of 18mm plywood that are 
    # glued together. Since they are simple, we just create the two
    # parts and glue them up, making sure to cut a hole in the center for
    # the screw that will attach them to the sled handle
    create part "rightBlock1" size 7.5cm,7.5cm at ($width+10cm),0,0 material "baltic-birch-18mm"
    create part "rightBlock2" size 7.5cm,7.5cm at ($width+10cm),0,18mm material "baltic-birch-18mm"
    cut "rightBlock1" circle at 3.75cm,3.75cm radius 8.5mm # Closest imperial approximation would be 11/32nds
    cut "rightBlock2" circle at 3.75cm,3.75cm radius 8.5mm # Closest imperial approximation would be 11/32nds
    join "rightBlock1" to "rightBlock2" with glue

    # The stop block is affixed to the handle with a t-slot that is 
    # made in the handle pieces. The slot is designed to accomodate an 
    # M8 screw with a fender washer in it. The handle closest to the 
    # operator accomodates the screw head and washer; the remaining 
    # pieces have a slot wide enough for the shaft of the screw, plus 
    # larger hole at the end of the slot to accomodate the introduction
    # of the head and washer into the handle.
    cut "handle1" rectangle at $width - 15.75cm, 3.75cm size 12cm, 25mm depth 8mm face back
    for $i = 2 to $handle_thickness
      cut "handle$i" rectangle at $width - 15.73cm, 3.75cm size 12cm, 8.5mm
      cut "handle$i" circle at ($width-3cm), 3.75cm radius 13mm
    end for

  end if


  # See if we are adding a stop block on the left
  if $stop_block_left then

    # The stop block is created as two pieces of 18mm plywood that are 
    # glued together. Since they are simple, we just create the two
    # parts and glue them up, making sure to cut a hole in the center for
    # the screw that will attach them to the sled handle
    create part "leftBlock1" size 7.5cm,7.5cm at -10cm,0,0 material "baltic-birch-18mm"
    create part "leftBlock2" size 7.5cm,7.5cm at -10cm,0,18mm material "baltic-birch-18mm"
    cut "leftBlock1" circle at 3.75cm,3.75cm radius 8.5mm # Closest imperial approximation would be 11/32nds
    cut "leftBlock2" circle at 3.75cm,3.75cm radius 8.5mm # Closest imperial approximation would be 11/32nds
    join "leftBlock1" to "leftBlock2" with glue

    # The stop block is affixed to the handle with a t-slot that is 
    # made in the handle pieces. The slot is designed to accomodate an 
    # M8 screw with a fender washer in it. The handle closest to the 
    # operator accomodates the screw head and washer; the remaining 
    # pieces have a slot wide enough for the shaft of the screw, plus 
    # larger hole at the end of the slot to accomodate the introduction
    # of the head and washer into the handle.
    cut "handle1" rectangle at 3cm, 3.75cm size ($width-48.5cm), 25mm depth 8mm face back
    for $i = 2 to $handle_thickness
      cut "handle$i" rectangle at 3cm, 3.75cm size  ($width-48.5cm), 8.5mm
      cut "handle$i" circle at 3cm, 3.75cm radius 13mm
    end for


 end if




end define
