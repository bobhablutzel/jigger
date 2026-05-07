# Common Scripting Pitfalls

A short collection of gotchas you'll likely hit while writing CADette scripts and templates. Most are corollaries of the grammar rather than bugs — they're predictable once you see the rule, but surprising the first time.

For cut-specific semantics (what cuts remove, vertex order, polygon vs spline), see [CUT_SEMANTICS.md](CUT_SEMANTICS.md).

## Parenthesis grouping means different things in different contexts

The trap: `(x, y)` looks like a "point" but the parser interprets it differently depending on where it appears.

**Inside polygon and spline cuts (and shape definitions):**
```
cut "panel" polygon (10, 10), (20, 10), (15, 25)
shape my_handle  spline  (0, 0), (50, 0), (50, 30), (0, 30)
```
Here `(x, y)` is a **vertex pair** — a single 2D point in a vertex list, where multiple pairs are comma-separated.

**Everywhere else** (including `rect`, `circle`, named-shape `at`, `create part ... at`, etc.):
```
cut "p" rect at 50, 100 size (a + b), c       # parens group ONE expression
cut "p" circle at ($w - 10), ($h - 5) radius 3
```
Here `(...)` is just a grouping paren around a single expression, like `(a + b) * c` in math. There is no "vertex pair" outside polygon/spline/shape contexts.

So this is a parse error:
```
# WRONG — parser opens paren, reads $w - 10, expects ), gets ',' → error
cut "p" circle at ($w - 10, $h - 5) radius 3
```

The fix is to give each coordinate its own grouping parens (or none, if the expressions are simple):
```
# RIGHT (per-coord parens, when expressions are complex)
cut "p" circle at ($w - 10), ($h - 5) radius 3

# RIGHT (no parens at all)
cut "p" circle at $w - 10, $h - 5 radius 3
```

Same trap applies to `rect ... size (a, b)`, `cut ... shape <name> at (a, b)`, and `create part ... at (x, y, z)`.

## Duplicate object names are rejected

`create part foo ...` followed by another `create part foo ...` returns an error:

```
An object named 'foo' already exists. Use a different name, or delete the existing object first.
```

Same applies across kinds — you can't have a part and a `create box` with the same name. Use `delete foo` to free the name first if you really want to recreate.

(Before this check existed, a duplicate `create` silently overwrote the registry entry while leaving the previous wrapper attached to the scene graph — producing a "ghost" that was visible in 3D but invisible to every command.)

## ANTLR error positions are approximate

When the parser hits a syntax error, the line and column it reports often point to where it gave up, not where the actual problem started. A typical pattern:

> `body line 24, column 40: missing ')' at ','`

...where line 24 looks fine, but a few lines later there's a malformed `circle at (a, b)` that's the real cause.

If the reported position looks structurally OK, scan a few lines back **and forward** for:
- Unbalanced parens
- Coordinate grouping bugs (see above)
- Missing keywords (`size`, `at`, `radius`, `material`, etc.)
- Stray characters

## Newlines are whitespace inside a command

A single command can span multiple lines:

```
create part "panel"
    size 60, 90
    material "plywood-18mm"
    at 0, 0, 0
```

This is one `create part` command, not four separate ones. Statements are delimited by the *start* of the next command keyword (`create`, `cut`, `move`, `rotate`, etc.), not by newlines.

So you can format long commands across lines for readability without worrying about line-continuation characters.

## Variable references vs bare identifiers

In template bodies, parameters bound by `params` are referenced with `$name`:

```
define standard/my_template params width(w), height(h)
  create part "side" size $width, $height ...
end define
```

Bare identifiers (no `$`) work too in some contexts (mostly inside `${...}` expression interpolation), but `$name` is the canonical form everywhere — use it consistently.

## Cut polarity, vertex order, self-intersection

The most-surprising cut-specific gotchas live in their own doc. See [CUT_SEMANTICS.md](CUT_SEMANTICS.md) for:
- Cuts remove material *inside* the closed boundary, not outside.
- Vertex order defines the polygon, not the bounding box.
- Self-intersecting polygons produce ambiguous results.
- Polygon (sharp) vs spline (smooth) — and the centripetal-bulge surprise.
- Closure-hint trick for spline fillets.
