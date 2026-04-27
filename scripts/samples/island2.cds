#! cadette
#
# Example script for creating an island made up of two 
# cabinets with a counter top.
#
# This script demonstrates the usage of for loops
# to create three identical cabinets

using none


# Create the base cabinets
create base_cabinet b0 w 80cm h 60cm d 40cm

# Use a for loop to create three identical cabinets next to each other
for $i = 1 to 3
   create base_cabinet "b$i" w 60cm h 60cm d 40cm right of "b${i-1}"
end for




# Create the counter and rotate it so that it is on the cabinets
create part counter material "granite-1-1/4" size (80+(3*60)+4)cm,45cm at 0,60cm,0
rotate counter 90,0,0
cut counter rect at 85cm,10cm size 50cm,25cm

