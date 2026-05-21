#! cadette
# Copyright 2026 Bob Hablutzel — Apache 2.0
# https://github.com/bobhablutzel/cadette
#
# First tutorial - creating a simple 60cm x 100cm x 20cm plywood box,
# made up of 18mm plywood, joined with pocket screws.


# First part
create part s1 material "plywood-18mm" size 60cm,20cm
move s1 to 0cm,0cm,98.2cm

# Second part
create part s2 material "plywood-18mm" size 96.4cm,20cm
rotate s2 0,270,0
move s2 to 60cm,0,1.8cm

# Third part
create part s3 material "plywood-18mm" size 96.4cm,20cm
rotate s3 0,270,0
move s3 to 1.8cm,0,1.8cm

# Fourth part
create part s4 material "plywood-18mm" size 60cm,20cm

# Joinery
join s2 to s1 with pocket_screw screws 2
join s3 to s1 with pocket_screw screws 2
join s2 to s4 with pocket_screw screws 2
join s3 to s4 with pocket_screw screws 2
