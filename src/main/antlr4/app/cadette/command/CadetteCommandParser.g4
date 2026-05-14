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

// Separate entry point used when a template body is parsed as a single unit
// (after `end define` collects its stored lines). A body is a sequence of
// commands and nested control-flow blocks. Loops and conditionals are
// first-class grammar productions rather than string-matched directives.
templateBody
    : templateStatement* EOF
    ;

templateStatement
    : ifBlock
    | forBlock
    | command
    ;

ifBlock
    : IF expression THEN thenBody+=templateStatement*
      (ELSE elseBody+=templateStatement*)?
      END IF
    ;

// `for $i = 1 to $n ... end for`. Both bounds inclusive. The loop variable
// is VAR_REF syntactically (matching how it's used — `$i`) and scoped to
// the body via the executor's scope stack.
forBlock
    : FOR VAR_REF ASSIGN expression TO expression
      templateStatement*
      END FOR
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
    | orientCommand
    | joinCommand
    | cutCommand
    | keepCommand
    | filletCommand
    | messageCommand
    | letCommand
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
    | validateCommand
    | shapeDefinitionCommand
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
    | LENGTH expression       // dimensional-lumber form: width defaulted from material
    | ORIENT orientation      // named initial orientation (lumber only)
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

// `rotate <obj> X, Y, Z` composes the rotation onto the object's current
// orientation (relative). `rotate <obj> by X, Y, Z` is the explicit form of
// the same. `rotate <obj> to X, Y, Z` is absolute — replaces the current
// rotation entirely. The relative-default matches how people iterate on
// orientation: "ok, now another quarter turn."
rotateCommand
    : ROTATE objectName rotationMode? rotation
    ;

rotationMode
    : BY          # rotateByMode
    | TO          # rotateToMode
    ;

rotation
    : expression COMMA expression COMMA expression
    ;

// `orient <obj> flat | on-edge | on-end` — named orientation shortcut for
// dimensional lumber. Composes a rotation that lays the board in the given
// shop-talk stance, replacing the current rotation (since "orient flat" is
// a goal state, not a delta).
orientCommand
    : ORIENT objectName orientation
    ;

orientation
    : FLAT        # orientFlat
    | ON_EDGE     # orientOnEdge
    | ON_END      # orientOnEnd
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

// Identifier-like contexts accept ID plus keyword tokens that users reasonably
// want as names — dimension keywords (WIDTH/HEIGHT/…) so templates can take
// params with those names, and unit-suffix keywords (MM/CM/…) so naming an
// object `cm` or a part `M` still works despite the unit-literal grammar.
nameLike
    : ID
    | WIDTH | HEIGHT | DEPTH | LENGTH | SIZE | COLOR | MATERIAL | GRAIN | PART | KERF
    | MM | CM | M | IN | FT | YD
    | FRONT | BACK
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

// `cut <part> <shape> [depth N]` — removes a region from a part.
// Shapes: rect, circle, polygon (vertex list). Spline is the next variant
// to land; the model already carries stubs for both polygon and spline.
cutCommand
    : CUT objectName cutShape
    ;

// `keep <part> <shape>` — same shape grammar as cut, opposite operation.
// Keeps only the material *inside* the shape; removes everything outside.
// Useful for defining non-rectangular outlines (curved handles, irregular
// shapes) by tracing what to retain rather than what to remove.
keepCommand
    : KEEP objectName cutShape
    ;

// `print|warn|error "message"` — emit a string (with optional ${var}
// interpolation) to the command output. None terminate the script in v1;
// they only differ in the prefix on the rendered line.
messageCommand
    : PRINT STRING     # printCommand
    | WARN STRING      # warnCommand
    | ERROR_KW STRING  # errorCommand
    ;

// `let $name = expr` — bind a name to an expression result in the innermost
// active variable scope. Top-level uses the always-on global scope; inside a
// template or for-loop body, binds in that scope (and is gone when the scope
// pops). Mutable — last assignment wins, same name can be rebound.
letCommand
    : LET VAR_REF ASSIGN expression
    ;

// `fillet <part> at x, y radius r facing <NE|NW|SE|SW> [depth d]` —
// rounds an outer 2D corner with a quarter-arc tangent to the two edges.
// Sugar over a polygon cut of the small wedge between the L-shape corner
// and the inscribed arc. The `facing` direction names the quadrant the
// corner *opens into* (i.e. where the empty space is, opposite the
// material side). Cardinal directions are parsed by the visitor as a
// nameLike (no new keyword pollution); numeric-degrees facing is a
// follow-up.
filletCommand
    : FILLET objectName AT expression COMMA expression
      RADIUS expression
      FACING nameLike
      (DEPTH expression)?
    ;

// Cut shape: keyword-prefixed shape with arg clauses. Rect/circle/named-shape
// take order-independent clauses (AT/SIZE/RADIUS/DEPTH); the visitor enforces
// which clauses are required for which shape and rejects mismatches. Polygon/
// spline/curve carry their data in a vertex list rather than clauses, so they
// just take an optional DEPTH at the end.
cutShape
    : RECT shapeArg+                                          # rectCutShape
    | CIRCLE shapeArg+                                        # circleCutShape
    | POLYGON vertexPair (COMMA vertexPair)* shapeArg*        # polygonCutShape
    | SPLINE vertexPair (COMMA vertexPair)* shapeArg*         # splineCutShape
    | CURVE vertexPair (COMMA vertexPair)* shapeArg*          # curveCutShape
    | SHAPE objectName shapeArg+                              # namedShapeCutShape
    | MITER FACING nameLike ANGLE expression shapeArg*        # miterCutShape
    ;

// Order-independent argument clauses. The visitor enforces which clauses are
// required (e.g. AT + SIZE for rect, AT + RADIUS for circle) and rejects
// nonsensical pairings (RADIUS on a rect, SIZE on a polygon, etc.).
shapeArg
    : AT expression COMMA expression           # shapeArgAt
    | SIZE expression COMMA expression         # shapeArgSize
    | RADIUS expression                        # shapeArgRadius
    | DEPTH expression                         # shapeArgDepth
    | FACE nameLike                            # shapeArgFace
    ;

// `shape <name> polygon|spline (x,y), (x,y), ...` — declares a named shape
// at the origin. Reused via `cut <part> shape <name> at x, y`. The shape's
// vertices are stored as-is and translated by the anchor at use time.
shapeDefinitionCommand
    : SHAPE objectName POLYGON vertexPair (COMMA vertexPair)*    # shapeDefPolygon
    | SHAPE objectName SPLINE  vertexPair (COMMA vertexPair)*    # shapeDefSpline
    ;

// Parenthesised (x, y) pair for vertex lists. Parens disambiguate
// against the variable-arity comma-separated coordinate streams that
// rect/circle use with their fixed coordinate counts.
vertexPair
    : LPAREN expression COMMA expression RPAREN
    ;

joinArg
    : DEPTH expression
    | SCREWS expression
    | SPACING expression
    ;

// Joint kinds are looked up by name in the visitor, not enumerated in the
// grammar — adding a new joint is a Java-only change (new Joint subclass +
// JointType enum entry). See CommandVisitor.visitJoinCommand.
jointType
    : ID
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
    | SCRIPT_PATH
    | THEME
    | THEMES
    ;

setCommand
    : SET UNITS unitName
    | SET MATERIAL materialName
    | SET KERF expression
    | SET SCRIPT_PATH scriptPathSpec
    | SET THEME themeName
    ;

themeName : ID | STRING ;

// `set script_path "..." [, "..."]` replaces the user-configured prefix
// of the search path; `set script_path none` clears it. The default tail
// (`~/.cadette/scripts/`, `./scripts/`) is always tried after the user
// entries — clearing the user portion just means falling back to defaults.
scriptPathSpec
    : NONE
    | STRING (COMMA STRING)*
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

// `validate` — geometric sanity check across joints. With no argument,
// validates every joint in the scene; with an assembly name, scopes to
// joints whose parts both belong to that assembly. Reports per-joint
// issues without changing scene state. See JointValidator for the predicates.
validateCommand
    : VALIDATE objectName?
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

// `name` or `name(alias)` optionally followed by `= <default-expression>`.
// Default expressions are evaluated left-to-right at instantiation time, so a
// later param's default can reference earlier params via $-ref.
paramDecl
    : paramName (LPAREN paramName RPAREN)? (ASSIGN expression)?
    ;

// Parameter names may shadow keyword tokens like 'width' — accept common
// keyword tokens here so they can be used as identifiers in template params.
// Unit-suffix keywords included for the same reason as in nameLike: naming
// a param `mm` or `cm` shouldn't suddenly be illegal just because the
// grammar gained unit literals.
paramName
    : ID
    | WIDTH | HEIGHT | DEPTH | LENGTH | SIZE | COLOR | MATERIAL | GRAIN | PART | KERF
    | MM | CM | M | IN | FT | YD
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
    // Generic single/multi-arg math function call. Recognised names: sin,
    // cos, tan, asin, acos, atan, atan2, sqrt, hypot, abs, pow, floor,
    // ceil, round, log, exp. Trig takes degrees (consistent with rotate/
    // orient); use `radians(d)` if you have a radian value. Unknown names
    // are a runtime error so we don't need a token per function.
    | ID LPAREN expression (COMMA expression)* RPAREN                       # idFuncCallExpr
    // Dimensional literal with explicit unit — `100mm`, `5cm`, `2.5in`.
    // Evaluates to "this many <unit>s expressed in the current units," so
    // it's self-describing and portable across `set units` calls. Binds
    // tighter than any binary op so `100mm + 5cm` parses as (100mm)+(5cm).
    | expression unitSuffix                                                 # unitSuffixExpr
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
    // Bare identifier as a variable reference. Keywords (WIDTH, MIN, etc.)
    // lex as themselves and never land here, so this doesn't collide with
    // command-level keyword uses. Needed so `${i - 1}` works without forcing
    // a second `$` inside the interpolation braces.
    | ID                                                                    # idRefExpr
    ;

unitSuffix : MM | CM | M | IN | FT | YD ;

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

// `set units <name>` accepts either the full enum name (e.g. 'millimeters',
// 'inches') — which lex as ID — or one of the unit-suffix abbreviations
// that are also keyword tokens (MM/CM/M/IN/FT/YD) after the unit-suffix
// grammar landed.
unitName
    : ID
    | MM | CM | M | IN | FT | YD
    ;
