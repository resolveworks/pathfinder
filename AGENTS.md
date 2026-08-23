# aletheia

A native Android chat app for the [pi](https://pi.dev) agent stack, being ported to a
pure Kotlin implementation.

## Scope

MVP: configure a provider, model, and API key; stream chat responses; switch between
persistent sessions. Agent tools, including `web_search` and `web_fetch`, are out of
scope. Configuration, chat streaming, and session switching all run on the native
Kotlin runtime (ZAI provider, OpenAI-completions API, OkHttp transport).

## Architecture

- `app/` — Kotlin, Jetpack Compose, MVVM/UDF, DataStore, and Android Keystore.
- Android owns UI, settings, credentials, and platform capabilities. The native
  agent runtime (`works.resolve.aletheia.ai`, `works.resolve.aletheia.agent`) owns agent, provider, and
  conversation behavior, including session persistence. `AletheiaApplication` wires
  the graph manually; no DI framework.

Keep dependencies manually wired unless their complexity justifies DI.

## Commands

```bash
./gradlew test assembleDebug
```

## Conventions

- Follow current Android documentation and `~/Projects/pi/packages/*/README.md`, not
  remembered APIs.
- Preserve the bleeding-edge Android toolchain; do not downgrade versions to fix
  compatibility issues.
- Prefer simple, conventional implementations over new abstraction layers.
- Never log API keys or persist them outside the Android Keystore-backed credential
  boundary.
