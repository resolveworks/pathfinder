# aletheia

A native Android chat app embedding `@earendil-works/pi-agent-core` and
`@earendil-works/pi-ai` in QuickJS.

## Scope

MVP: configure a provider, model, and API key; stream chat responses; switch between
persistent sessions. Agent tools, including `web_search` and `web_fetch`, are out of
scope.

## Architecture

- `app/` — Kotlin, Jetpack Compose, MVVM/UDF, DataStore, Android Keystore, and the
  QuickJS host.
- `agent-js/` — TypeScript pi runtime bundled by esbuild to the ignored
  `app/src/main/assets/agent.js` asset.
- Kotlin and JavaScript communicate through an explicit JSON command/event boundary.
- pi owns agent, provider, and conversation behavior, including session persistence
  through `JsonlSessionRepo`.
- Android owns UI, settings, credentials, and narrow platform capabilities such as
  HTTP and filesystem access.

Keep dependencies manually wired unless their complexity justifies DI.

## Commands

```bash
pnpm --dir agent-js check
pnpm --dir agent-js build
./gradlew test assembleDebug
```

Rebuild the JS asset after changing `agent-js/`.

## Conventions

- Follow current Android documentation and `~/Projects/pi/packages/*/README.md`, not
  remembered APIs.
- Preserve the bleeding-edge Android toolchain; do not downgrade versions to fix
  compatibility issues.
- Keep the Kotlin wrapper thin and the JSON bridge explicit.
- Prefer simple, conventional implementations over new abstraction layers.
- Never log API keys or persist them outside the Android Keystore-backed credential
  boundary.
