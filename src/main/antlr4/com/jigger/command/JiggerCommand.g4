//
// Copyright 2026 Bob Hablutzel
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
// Source: https://github.com/bobhablutzel/jigger
//

grammar JiggerCommand;

// Parser rules
command
    : createCommand
    | createPartCommand
    | deleteCommand
    | moveCommand
    | alignCommand
    | resizeCommand
    | rotateCommand
    | joinCommand
    | displayCommand
    | hideCommand
    | listCommand
    | showCommand
    | setCommand
    | exportCommand
    | undoCommand
    | redoCommand
    | helpCommand
    | exitCommand
    ;

createCommand
    : CREATE shape objectName (AT position)? sizeSpec? (COLOR color)?
    ;

createPartCommand
    : CREATE PART objectName (MATERIAL materialName)? SIZE partSize (AT position)? (GRAIN grainReq)?
    ;

deleteCommand
    : DELETE (objectName | ALL)
    ;

moveCommand
    : MOVE objectName TO position
    | MOVE objectName relativePosition
    ;

relativePosition
    : TO direction OF? objectName (GAP NUMBER)?
    ;

direction
    : LEFT_KW
    | RIGHT_KW
    | BEHIND
    | IN_FRONT
    | ABOVE
    | BELOW
    ;

alignCommand
    : ALIGN face OF objectNameList WITH objectName
    ;

face
    : FRONT
    | BACK
    | LEFT_KW
    | RIGHT_KW
    | TOP
    | BOTTOM
    ;

objectNameList
    : objectName (COMMA objectName)*
    ;

resizeCommand
    : RESIZE objectName sizeSpec
    ;

rotateCommand
    : ROTATE objectName rotation
    ;

rotation
    : NUMBER COMMA NUMBER COMMA NUMBER
    ;

objectName
    : STRING
    | ID
    ;

materialName
    : STRING
    | ID
    ;

partSize
    : NUMBER COMMA NUMBER
    ;

grainReq
    : VERTICAL
    | HORIZONTAL
    | ANY_KW
    ;

joinCommand
    : JOIN objectName TO objectName WITH jointType (DEPTH NUMBER)? (SCREWS NUMBER)? (SPACING NUMBER)?
    ;

jointType
    : BUTT_JT
    | DADO_JT
    | RABBET_JT
    | POCKET_SCREW_JT
    ;

displayCommand
    : DISPLAY NAMES              // all objects
    | DISPLAY NAME objectName    // single object
    ;

hideCommand
    : HIDE NAMES                 // all objects
    | HIDE NAME objectName       // single object
    ;

listCommand
    : LIST
    ;

showCommand
    : SHOW showTarget
    | SHOW INFO objectName
    | SHOW TEMPLATE objectName
    ;

showTarget
    : UNITS
    | OBJECTS
    | MATERIALS
    | TEMPLATES
    | JOINTS
    | CUTLIST
    | BOM
    | LAYOUT
    ;

setCommand
    : SET UNITS unitName
    | SET MATERIAL materialName
    | SET KERF NUMBER
    | SET LAYOUT layoutMode
    ;

layoutMode
    : TABS
    | SPLIT
    ;

exportCommand
    : EXPORT CUTLIST exportFormat (STRING)?
    ;

exportFormat
    : PDF
    | PNG
    | JPEG
    ;

undoCommand
    : UNDO
    ;

redoCommand
    : REDO
    ;

helpCommand
    : HELP
    ;

exitCommand
    : EXIT
    ;

shape
    : BOX
    | SPHERE
    | CYLINDER
    ;

position
    : NUMBER COMMA NUMBER COMMA NUMBER
    ;

// Size can be specified as:
//   size 2               — uniform
//   size 1,2,3           — w,h,d as a tuple
//   width 1 height 2 depth 3  — named (any order, each optional, default 1)
sizeSpec
    : SIZE dimensions                          # sizeByDimensions
    | (widthSpec | heightSpec | depthSpec)+     # sizeByComponents
    ;

dimensions
    : NUMBER (COMMA NUMBER COMMA NUMBER)?
    ;

widthSpec
    : WIDTH NUMBER
    ;

heightSpec
    : HEIGHT NUMBER
    ;

depthSpec
    : DEPTH NUMBER
    ;

color
    : RED | GREEN | BLUE | YELLOW | WHITE | HEX_COLOR
    ;

unitName
    : ID
    ;

// Lexer rules
CREATE     : 'create' | 'cr' ;
DELETE     : 'delete' | 'del' ;
MOVE       : 'move' | 'mv' ;
RESIZE     : 'resize' ;
ROTATE     : 'rotate' | 'rot' ;
JOIN       : 'join' ;
WITH       : 'with' ;
BUTT_JT    : 'butt' ;
DADO_JT    : 'dado' ;
RABBET_JT  : 'rabbet' ;
POCKET_SCREW_JT : 'pocket_screw' | 'pocket-screw' | 'pocketscrew' | 'pocket' ;
SCREWS     : 'screws' ;
SPACING    : 'spacing' ;
JOINTS     : 'joints' ;
CUTLIST    : 'cutlist' | 'cut-list' | 'cutsheet' | 'cut-sheet' ;
BOM        : 'bom' ;
EXPORT     : 'export' ;
PDF        : 'pdf' ;
PNG        : 'png' ;
JPEG       : 'jpeg' | 'jpg' ;
LAYOUT     : 'layout' | 'layouts' ;
KERF       : 'kerf' ;
TABS       : 'tabs' | 'tabbed' ;
SPLIT      : 'split' | 'split-pane' | 'side-by-side' ;
ALIGN      : 'align' ;
GAP        : 'gap' ;
OF         : 'of' ;
// Direction / face tokens (used by both relative positioning and align)
IN_FRONT   : 'in front' | 'in-front' ;
LEFT_KW    : 'left' ;
RIGHT_KW   : 'right' ;
BEHIND     : 'behind' ;
ABOVE      : 'above' ;
BELOW      : 'below' ;
FRONT      : 'front' ;
BACK       : 'back' ;
TOP        : 'top' ;
BOTTOM     : 'bottom' ;
TO         : 'to' ;
DISPLAY    : 'display' ;
HIDE       : 'hide' ;
NAME       : 'name' ;
NAMES      : 'names' ;
PART       : 'part' | 'p' ;
MATERIAL   : 'material' | 'mat' ;
GRAIN      : 'grain' | 'gr' ;
VERTICAL   : 'vertical' | 'vert' | 'v' ;
HORIZONTAL : 'horizontal' | 'horiz' | 'hz' ;
ANY_KW     : 'any' ;
MATERIALS  : 'materials' ;
TEMPLATE   : 'template' ;
TEMPLATES  : 'templates' ;
INFO       : 'info' ;
LIST       : 'list' | 'ls' ;
SHOW       : 'show' ;
SET        : 'set' ;
OBJECTS    : 'objects' ;
UNITS      : 'units' ;
UNDO       : 'undo' ;
REDO       : 'redo' ;
HELP       : 'help' | '?' ;
EXIT       : 'exit' | 'quit' | 'q' ;
AT         : 'at' | '@' ;
SIZE       : 'size' | 'sz' ;
WIDTH      : 'width' | 'w' ;
HEIGHT     : 'height' | 'h' ;
DEPTH      : 'depth' | 'd' ;
COLOR      : 'color' | 'col' ;
ALL        : 'all' ;

BOX        : 'box' ;
SPHERE     : 'sphere' ;
CYLINDER   : 'cylinder' ;

RED        : 'red' ;
GREEN      : 'green' ;
BLUE       : 'blue' ;
YELLOW     : 'yellow' ;
WHITE      : 'white' ;

COMMA      : ',' ;
NUMBER     : '-'? ( [0-9]+ ('.' [0-9]*)? | '.' [0-9]+ ) ;
HEX_COLOR  : '#' [0-9a-fA-F]+ ;
STRING     : '"' ~["]* '"' ;
ID         : [a-zA-Z_] [a-zA-Z0-9_]* ;
WS         : [ \t\r\n]+ -> skip ;
