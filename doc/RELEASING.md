# Releasing CADette

How to cut a release and publish it on GitHub. This is a maintainer document —
end users only ever see the [Releases page](https://github.com/bobhablutzel/cadette/releases).

CADette has no CI release pipeline. A release is built **by hand on each of the
three target OSes**, because `jpackage` cannot cross-build: a `.deb` must be
built on Linux, a `.dmg` on macOS, an `.msi` on Windows. Plan for access to all
three machines before you start.

## Overview

1. Decide the version and update `pom.xml`.
2. Commit the version bump and tag it.
3. Build the installer on each platform.
4. Create the GitHub Release and upload all three installers.
5. Push the tag and publish.

## 1. Version and tag naming

The single source of truth for the version is `<version>` in `pom.xml`.

- **Development** versions carry `-SNAPSHOT` (e.g. `1.0.0-SNAPSHOT`).
- **Release** versions drop it. The git tag is `v` + the version, with any
  pre-release qualifier kept: `v1.0.0-beta.1`, `v1.0.0`, `v1.1.0`.

Note that `jpackage` rejects SemVer pre-release qualifiers in its `--app-version`
argument, so the build strips the version down to its numeric `major.minor.patch`
portion. This means **installer filenames always show the numeric version only**
— `Cadette-1.0.0.dmg` even for the `v1.0.0-beta.1` tag. The `-beta.1` lives in
the git tag and the GitHub Release name, not the artifact filenames. That is
expected; don't try to "fix" it.

To set the release version, edit `pom.xml`:

```xml
<version>1.0.0-beta.1</version>
```

(Keep the qualifier here too — it shows up in `show about` and `build-info.properties`
via the git-commit-id plugin, so users can see they're on a beta.)

## 2. Commit and tag

```bash
# On main, with a clean tree and tests green:
mvn test
git add pom.xml
git commit -m "Release v1.0.0-beta.1"
git tag -a v1.0.0-beta.1 -m "CADette v1.0.0-beta.1"
```

Don't push the tag yet — build and smoke-test the installers first so a bad
build doesn't leave a published tag pointing at it. If something is wrong you
can still `git tag -d v1.0.0-beta.1` and redo the commit.

## 3. Build the installer on each platform

Check out the **tagged commit** on each machine (`git checkout v1.0.0-beta.1`)
so all three installers are built from identical source.

The `package-app` Maven profile auto-detects the host OS and produces the
right installer type. Use `verify`, **not** `package` — the macOS build needs
a verify-phase step to wrap the app into a `.dmg`:

```bash
mvn -P package-app clean verify -DskipTests
```

Output lands in `target/dist/`:

| Platform | Artifact | Path |
|----------|----------|------|
| Linux    | `.deb`   | `target/dist/cadette_1.0.0_amd64.deb` |
| macOS    | `.dmg`   | `target/dist/Cadette-1.0.0.dmg` |
| Windows  | `.msi`   | `target/dist/Cadette-1.0.0.msi` |

Per-platform build requirements:

- **Linux** — `jpackage` on PATH (ships with JDK 25).
- **macOS** — `jpackage` on PATH. The build is two-step: it produces an `.app`
  directory (app-image) with `--java-options` baked into the config, then wraps
  that into a `.dmg`. This works around a jpackage 25.0.2 bug where direct
  `.dmg` builds drop `--java-options`. Both steps run automatically under
  `verify`.
- **Windows** — `jpackage` on PATH plus the WiX Toolset v3.0+ (jpackage shells
  out to WiX to build the `.msi`).

**Smoke-test each installer** before publishing: install it, launch the app,
run `show about` and confirm the commit matches the tagged commit, and create a
template (e.g. `create crosscut_sled S w 80cm l 60cm fh 12cm`).

Collect all three artifacts onto one machine for the upload step.

## 4. Create the GitHub Release

Use the `gh` CLI (authenticated as a repo maintainer). Push the tag first so
the release attaches to it:

```bash
git push origin main
git push origin v1.0.0-beta.1
```

Then create the release and upload all three installers in one command:

```bash
gh release create v1.0.0-beta.1 \
  --title "CADette v1.0.0-beta.1" \
  --notes-file release-notes.md \
  --prerelease \
  target/dist/cadette_1.0.0_amd64.deb \
  Cadette-1.0.0.dmg \
  Cadette-1.0.0.msi
```

- `--prerelease` — use this for any `-beta` / `-alpha` / `-rc` tag. Drop it for
  a final release. It keeps the release out of the "Latest" slot.
- `--notes-file` — point at a Markdown file with the release notes (see below).
  You can also use `--notes "..."` for short notes, or `--generate-notes` to
  auto-build them from merged PRs.

## 5. Release notes

Write `release-notes.md` covering, at minimum:

- **What CADette is** (one line) and that it's an alpha/beta preview.
- **What's new** in this release, or for the first release, the headline
  features.
- **Known limitations** — no save/load yet, installers are unsigned, lumber
  prices are estimates. Keep this in sync with the Status section of
  [README.md](../README.md).
- **Install instructions** — or a pointer to the per-platform notes in the
  README, which already cover the unsigned-installer warnings (apt `./` prefix,
  macOS right-click→Open, Windows SmartScreen "Run anyway").
- **How to report bugs** — GitHub Issues, or feedback@cadette.app.

## After the release

- Bump `pom.xml` back to a `-SNAPSHOT` version for continued development
  (e.g. `1.0.0-beta.2-SNAPSHOT` or `1.0.0-SNAPSHOT`), commit, and push.
- Verify the [Releases page](https://github.com/bobhablutzel/cadette/releases)
  shows all three installers and the notes render correctly.
- Update the version numbers in the README install snippets if the numeric
  version changed (`cadette_1.0.0_amd64.deb` etc.).

## Checklist

```
[ ] Tests green (mvn test)
[ ] pom.xml version set to release version
[ ] Version-bump commit made
[ ] Tag created (v<version>)
[ ] Linux .deb built + smoke-tested
[ ] macOS .dmg built + smoke-tested
[ ] Windows .msi built + smoke-tested
[ ] release-notes.md written
[ ] Tag + main pushed
[ ] gh release create run with all 3 artifacts
[ ] Releases page verified
[ ] pom.xml bumped back to -SNAPSHOT
```
