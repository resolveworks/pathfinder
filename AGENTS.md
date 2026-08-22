# aletheia

A native Android chat app wrapping the [pi](https://pi.dev) agent stack.

## What this is

A minimal, "modern and boring" Android app (Kotlin, Jetpack Compose, MVVM/UDF)
that embeds the pi agent runtime (`@earendil-works/pi-agent-core` + `@earendil-works/pi-ai`)
as a bundled JS file executed by an embedded JS engine. The Kotlin wrapper stays thin:
UI, settings, storage, and a small capability bridge (`httpFetch`).

MVP scope: configure model/provider/API key, switch between sessions, chat with
streaming responses. Tools (web_search, web_fetch) are explicitly out of scope for now.

## Layout

- `app/` — Android app module (Kotlin, Compose, Hilt, DataStore)
- `agent-js/` — TypeScript agent runtime, bundled with esbuild into an app asset; Pi's `JsonlSessionRepo` owns conversation persistence

## Build

See README.md. Status: scaffolding in progress.

## Conventions

- Follow current official documentation (developer.android.com, docs in ~/Projects/pi/packages/*/README.md)
  rather than memory — versions move fast.
- Keep the wrapper thin: agent logic lives in `agent-js/`, Android provides capabilities.
- Boring choices over clever ones.
