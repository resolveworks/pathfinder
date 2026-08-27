# Pathfinder

Pathfinder is a minimal native Android interface for [pi](https://pi.dev). It
ports pi's relevant agent and model runtime to pure Kotlin, then surrounds it
with a thin, conventional Android application.

The project is intentionally not a separate interpretation of pi and not a
literal copy of pi's terminal UI. Pi defines the runtime behavior; Android
defines the mobile interaction layer.

## Direction

- **Port, do not reinvent.** Kotlin runtime behavior follows the current pi
  sources as closely as the language and platform allow. Concepts, event
  streams, provider behavior, conversation trees, and failure semantics should
  stay recognizable and comparable to upstream.
- **Use Android defaults.** The application favors Jetpack Compose, Material 3,
  dynamic system styling, and standard lifecycle, navigation, persistence, and
  security facilities over custom framework or design-system code.
- **Stay minimal.** Capabilities are added by porting useful pi behavior, not by
  growing parallel abstractions or speculative app-specific features. The
  Android shell should remain smaller and simpler than the runtime it exposes.
- **Track the platform.** The primary target is the latest GrapheneOS release
  on supported devices, using the newest Android platform, Kotlin toolchain,
  and AndroidX stack. Compatibility is moved forward rather than maintained by
  downgrading dependencies.

These are long-term constraints rather than a snapshot feature list. They are
intended to keep semantic drift and maintenance cost low as both pi and Android
evolve.

## Architecture

The codebase has two boundaries:

- The native Kotlin runtime owns model and provider behavior, streaming, agent
  state, and conversation semantics. `works.resolve.pathfinder.ai` follows pi's
  `packages/ai`, while `works.resolve.pathfinder.agent` follows
  `packages/agent`; session behavior likewise follows pi's branching session
  model.
- The Android shell owns Compose UI, lifecycle, navigation, settings, secure
  credentials, persistence adapters, and other platform capabilities. It
  projects runtime state through a conventional MVVM/UDF flow instead of
  reimplementing agent behavior in the UI.

The application uses a single activity, state-hoisted Compose surfaces, and a
small manually wired dependency graph. Platform defaults are preferred over
custom components, and new architectural machinery is added only when it
reduces rather than increases total complexity.

When a faithful port is impossible or inappropriate on Android, the divergence
is kept at the narrowest boundary and documented against the corresponding pi
source.

## Upstream synchronization

A local pi checkout is the source of truth for ported behavior. By default,
tooling expects it at `~/Projects/pi`; set `PI_REPO_DIR` to use another
checkout. Runtime changes should be compared against current source and package
documentation under `packages/`, rather than against remembered pi behavior.

The bundled model catalog is generated from pi and must not be edited by hand:

```bash
node tools/generate-model-catalog.mjs          # PI_REPO_DIR or ~/Projects/pi
```

Generation selects the catalog surface supported by the Kotlin runtime and
records the source pi revision.

## Build

Install a JDK and the Android SDK/Build Tools versions declared by the Gradle
configuration, set `ANDROID_HOME`, then run:

```bash
./gradlew test assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Security and diagnostics

Provider credentials are stored per provider behind Android Keystore-backed
encryption. API keys, message content, and model responses must never be
logged.

Operational lifecycle events use the `Pathfinder` Logcat tag:

```bash
adb logcat -v time -s Pathfinder
```
