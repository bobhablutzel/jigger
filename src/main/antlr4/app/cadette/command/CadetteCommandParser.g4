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

parser grammar CadetteCommandParser;

options {
    tokenVocab = CadetteCommandLexer;
}

// Top-level entry: a single input may be empty (e.g. a pure-comment line, since
// LINE_COMMENT is skipped at lex time) or a single command.
input
    : command? EOF
    ;

command
    : createCommand
    | createPartCommand
    | createTemplateCommand
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
    | defineCommand
    | usingCommand
    | whichCommand
    | statsCommand
    | runCommand
    ;

createCommand
    : CREATE shape objectName createArg*
    ;

// Sub-clauses for create: any order. Repeats are accepted by the grammar;
// the visitor uses last-wins.
createArg
    : atPlacement
    | sizeSpec
    | COLOR color
    ;

// SIZE is required semantically but appears as one of several keyword clauses;
// the visitor validates that a partSize arg is present.
createPartCommand
    : CREATE PART objectName partArg*
    ;

partArg
    : MATERIAL materialName
    | SIZE partSize
    | atPlacement
    | GRAIN grainReq
    ;

createTemplateCommand
    : CREATE templateRef objectName templateArg*
    ;

// Template references accept bare names (ID / keyword tokens), slash-qualified
// names ("standard/cabinets/base_cabinet"), or quoted strings.
templateRef
    : QUALIFIED_NAME
    | STRING
    | nameLike
    ;

templateArg
    : paramValuePair
    | atPlacement
    | relativePosition
    ;

atPlacement
    : AT position
    ;

paramValuePair
    : paramName expression
    ;

deleteCommand
    : DELETE (objectName | ALL)
    ;

moveCommand
    : MOVE objectName TO position
    | MOVE objectName relativePosition
    ;

relativePosition
    : TO? direction OF? objectName (GAP expression)?
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
    : expression COMMA expression COMMA expression
    ;

// Object and material names must also accept keyword tokens that happen to have
// single-letter aliases (e.g. 'D' lexes as DEPTH), since users naturally pick
// short letters for instance names like "create base_cabinet D ...".
objectName
    : STRING
    | nameLike
    ;

materialName
    : STRING
    | nameLike
    ;

nameLike
    : ID
    | WIDTH | HEIGHT | DEPTH | SIZE | COLOR | MATERIAL | GRAIN | PART | KERF
    ;

partSize
    : expression COMMA expression
    ;

grainReq
    : VERTICAL
    | HORIZONTAL
    | ANY_KW
    ;

joinCommand
    : JOIN objectName TO objectName WITH jointType joinArg*
    ;

joinArg
    : DEPTH expression
    | SCREWS expression
    | SPACING expression
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
    | SHOW TEMPLATE templateRef
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
    | SET KERF expression
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
    | CSV
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

defineCommand
    : DEFINE templateRef (PARAMS paramDecl (COMMA paramDecl)*)?
    ;

// `using standard/cabinets` — scopes bare template references so
// `create base_cabinet` resolves to `standard/cabinets/base_cabinet`.
// Script-scoped: a `using` inside a `run`-invoked script is undone when
// the script finishes. A `using` inside the startup script persists.
usingCommand
    : USING templateRef     # usingAdd
    | USING NONE            # usingClear
    ;

// `which base_cabinet` — prints the fully-qualified name that would be
// resolved, and the file it came from. Uses the same resolution path as
// `create`, so it faithfully reports what `create` would pick.
whichCommand
    : WHICH templateRef
    ;

statsCommand
    : STATS
    ;

// The RUN token switches the lexer to PATH_MODE so that path characters
// (slashes, dots, variables, quoted strings) tokenize as PATH_* tokens.
runCommand
    : RUN pathExpr?
    ;

pathExpr
    : pathSegment+
    ;

pathSegment
    : PATH_LITERAL
    | PATH_VAR
    | PATH_QUOTED
    ;

paramDecl
    : paramName (LPAREN paramName RPAREN)?
    ;

// Parameter names may shadow keyword tokens like 'width' — accept common
// keyword tokens here so they can be used as identifiers in template params.
paramName
    : ID
    | WIDTH | HEIGHT | DEPTH | SIZE | COLOR | MATERIAL | GRAIN | PART | KERF
    ;

shape
    : BOX
    | SPHERE
    | CYLINDER
    ;

// Arithmetic and logical expression. Left-recursive — ANTLR 4 handles the
// precedence via alternative ordering (earlier binds tighter), same idiom as
// the Java grammar. Result is numeric; comparisons and logical ops produce
// 1.0 / 0.0 under numeric truthiness (no separate boolean type today).
expression
    : LPAREN expression RPAREN                                              # parenExpr
    | (MIN | MAX) LPAREN expression (COMMA expression)+ RPAREN              # funcCallExpr
    | MINUS expression                                                      # negExpr
    | NOT expression                                                        # notExpr
    | expression op=(STAR | SLASH) expression                               # mulExpr
    | expression op=(PLUS | MINUS) expression                               # addExpr
    | expression op=(LT | LTE | GT | GTE) expression                        # relExpr
    | expression op=(EQ | NEQ) expression                                   # eqExpr
    | expression AND expression                                             # andExpr
    | expression OR expression                                              # orExpr
    | NUMBER                                                                # numberExpr
    | VAR_REF                                                               # varRefExpr
    ;

position
    : expression COMMA expression COMMA expression
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
    : expression (COMMA expression COMMA expression)?
    ;

widthSpec
    : WIDTH expression
    ;

heightSpec
    : HEIGHT expression
    ;

depthSpec
    : DEPTH expression
    ;

color
    : RED | GREEN | BLUE | YELLOW | WHITE | HEX_COLOR
    ;

unitName
    : ID
    ;
