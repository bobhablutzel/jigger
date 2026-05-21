# Contributing to CADette

Thanks for your interest in improving CADette. This document covers how to get
set up, the ways you can contribute, and the conventions the project follows.

CADette is in early alpha — the most valuable contributions right now are bug
reports, new templates, and fixes for the rough edges that real use turns up.

## Getting set up

Clone the sources from GitHub. Make sure you have the dependencies installed;
CADette builds with **JDK 25** and **Maven 3.8+**.

```bash
git clone https://github.com/bobhablutzel/cadette.git
```

## Building from source

CADette uses Maven as the build framework:

```bash
mvn exec:exec
```

The headless test suite (450+ tests, no display required) runs with `mvn test`.

To build an installer, the `package-app` profile auto-detects the host OS and
selects the right installer type:

```bash
# Use `verify`, not `package` — the macOS build needs a verify-phase wrap step
# to produce the .dmg from the staging .app.
mvn -P package-app clean verify -DskipTests
```

| Platform | Artifact | Path |
|----------|----------|------|
| Linux | `.deb` installer | `target/dist/cadette_1.0.0_amd64.deb` |
| macOS | `.dmg` installer | `target/dist/Cadette-1.0.0.dmg` |
| Windows | `.msi` installer | `target\dist\Cadette-1.0.0.msi` |

Build requirements per platform:

- **macOS**: `jpackage` on PATH. The build produces an `.app` directory
  (app-image) and wraps it into a `.dmg`: jpackage 25.0.2 on macOS has a bug
  where direct `.dmg` builds drop `--java-options` from the inner app's config
  (which is needed for `-XstartOnFirstThread`).
- **Windows**: `jpackage` on PATH plus WiX Toolset v3.0+ (jpackage requires
  `light.exe`/`candle.exe` for `.msi` generation).

Code signing for both platforms is on the post-F&F backlog.

## Ways to contribute

### Templates — the easiest place to start

A template is a parametric recipe for an assembly, written in the CADette
command language. Contributing one needs no Java:

1. Write a `define … end define` block. The
   [scripting reference](doc/SCRIPTING.md#templates) covers the syntax, and the
   bundled templates under `src/main/resources/templates/` are worked examples.
2. Save it as a `.cds` file in the appropriate category directory. Generally
   you should use a directly that aligns with you or your company under templates.
   However, if you want to add to the standard set
   (`standard/cabinets/`, `standard/jigs/`, `standard/carpentry/`, …) send me a note and
   we can discuss. I'm very open to it!
3. Add an entry under [`examples/`](examples/) if it's worth a walkthrough.

Templates are the fastest way to broaden what CADette can do — if you build a
jig or cabinet you'd reuse, it probably belongs here.

### Bug fixes

Pick up an open [issue](https://github.com/bobhablutzel/cadette/issues), or fix
something you hit yourself. Small, focused fixes are easiest to review.

### Joint types, materials, and engine features

These are Java changes. [ARCHITECTURE.md](doc/ARCHITECTURE.md) maps the
codebase and has extension guides for adding a material, a joint type, or a
template — new materials are catalog entries, and new joint types are a new
`Joint` record plus a `JointType` enum value (no grammar change required). If
anything there is unclear, open an issue and we'll point you at the right
files.

### Documentation

Corrections and clarifications to the README, the scripting reference, and the
tutorials are all welcome.

## Conventions

- **Build tool:** Maven. There is no Gradle build.
- **Language level:** Java 25.
- **Boilerplate:** the project uses [Lombok](https://projectlombok.org/) —
  prefer its annotations over hand-written getters, builders, and constructors.
- **Style:** favour streams over imperative loops where it stays readable; fall
  back to a loop when a stream would force gymnastics.
- **License headers:** new source files carry the Apache 2.0 header used
  throughout the codebase.
- **Tests:** add tests for new behaviour and bug fixes. The suite is headless —
  `mvn test` must pass before a change is ready.

## Submitting a change

1. Fork the repository and create a feature branch.
2. Make your change; keep commits focused and the suite green (`mvn test`).
3. Open a pull request describing what changed and why. Reference any related
   issue.
4. If I don't pick it up / comment on it soon, ping me at feedback@cadette.app.

## License

By contributing, you agree that your contributions are licensed under the
[Apache License, Version 2.0](LICENSE), the same license as the project.
