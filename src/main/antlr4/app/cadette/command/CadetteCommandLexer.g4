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
// Source: https://github.com/bobhablutzel/cadette
//

lexer grammar CadetteCommandLexer;

options {
    caseInsensitive = true;
}

// ---- Default mode (command keywords and identifiers) ----

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
CSV        : 'csv' ;
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
DEFINE     : 'define' ;
PARAMS     : 'params' | 'param' ;
STATS      : 'stats' ;
RUN        : 'run' -> pushMode(PATH_MODE) ;
LPAREN     : '(' ;
RPAREN     : ')' ;
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
LINE_COMMENT : '#' ~[\r\n]* -> skip ;

// ---- Path mode ----
// Entered from RUN. Tokenizes the rest of the line as path segments so that
// paths can contain slashes, dots, and $variable references without needing
// quotes. Quoted paths still supported (for paths with spaces).
mode PATH_MODE;

// Newlines terminate path parsing and pop back to default mode. Guards
// against multi-line inputs (where one lexer sees more than a single command
// line) by re-entering the normal keyword vocabulary on the next line.
PATH_NEWLINE  : [\r\n]+ -> popMode, skip ;
PATH_WS       : [ \t]+ -> skip ;
PATH_COMMENT  : '#' ~[\r\n]* -> skip ;
PATH_VAR      : '$' [a-zA-Z_] [a-zA-Z0-9_]* ;
PATH_QUOTED   : '"' ~["\r\n]* '"' ;
PATH_LITERAL  : [a-zA-Z0-9_./\\~:+=@-]+ ;
