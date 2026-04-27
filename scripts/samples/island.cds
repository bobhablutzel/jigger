#! cadette
#
# Example script for creating an island made up of two 
# cabinets with a counter top.
#
# End result is two cabinets with a granite countertop

using none

# Create the two base cabinets
create base_cabinet b w 80cm h 60cm d 40cm
create base_cabinet b2 w 60cm h 60cm d 40cm right of b

# Create the counter and rotate it so that it is on the cabinets
create part counter material "granite-1-1/4" size 145cm,45cm at 0,60cm,0
rotate counter 90,0,0


