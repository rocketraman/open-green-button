# Open Green Button — agent guide

## Toolchains: use and update mise (IMPORTANT)

This repo's toolchain (JDKs, Gradle, Node, etc.) is managed by **mise** — pinned in
[`mise.toml`](mise.toml). Always go through mise; do not assume the ambient `java`/`gradle` on
`PATH` is correct (the host may default to JDK 25, which breaks Gradle 8.14).

- **Run build tools through mise**: `mise exec -- ./gradlew …` (or rely on mise being active in
  the shell). The bare shell's `java` may be the wrong JDK.
- **When you add, remove, or change a tool or version — update `mise.toml`** (and run
  `mise install`). Never hardcode a tool path in scripts or docs when mise can provide it; wire it
  through `mise.toml` (`[tools]` for versions, `[env]` for derived paths) so it's automatic for
  everyone. Keep any version that appears in more than one place (e.g. a tool version and a path
  in `[env]`) in sync, and update the explanatory comments in `mise.toml` too.
- After editing `mise.toml`, verify with `mise install` + `mise env` (and a real build) before
  considering it done.

### Current JDK setup (and why)
- `java = "graalvm-community-24.0.2"` — ONE JDK for everything: it runs the Gradle daemon,
  satisfies the project's `jvmToolchain(24)` compile target, and supplies `native-image` + the
  tracing agent. GraalVM for JDK 23+ is required to read the unified `reachability-metadata.json`
  format the bootable library bundles inside its jars (GraalVM 21 silently ignores it). JDK 24
  rather than 25 keeps Gradle 8.14 happy — it can't yet read the JDK 25 class-file format — and
  sidesteps the tracing-agent regression with Ktor CIO (oracle/graal#12650).
- `GRAALVM_HOME` (set in `mise.toml` `[env]`) points the GraalVM Native Build Tools Gradle plugin
  at that install, so `:app:nativeCompile` and `scripts/generate-native-metadata.sh` work with no
  manual `JAVA_HOME` juggling. It repeats the version from `[tools]` — change both together.

## Build & native image
- App lives under [`server/`](server/) (Kotlin, Ktor CIO, Gradle). Normal build: `./gradlew build`.
- The production deploy artifact is a **GraalVM native image** (Fly.io scale-to-zero). See
  [`Dockerfile`](Dockerfile), [`docs/deployment.md`](docs/deployment.md), and the native-image
  reachability metadata under
  `server/app/src/main/resources/META-INF/native-image/org.opengb/` (regenerate with
  `scripts/generate-native-metadata.sh`; `manual/` there is hand-maintained — read its README).
- A missing metadata entry only fails at **runtime**, so after regenerating, rebuild and confirm
  the binary/container actually boots and serves before committing.
