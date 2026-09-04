# Pathfinder

Pathfinder is the Android app I was missing in my own day-to-day work. I do a
lot of research from my phone and use a wide range of models—especially open
models spread across different providers—but I could not find a simple native
app that brought them together in the way I wanted.

I am building Pathfinder for myself first, but sharing it in case it is useful
to others. Its priorities will naturally follow how I use it rather than a
product roadmap.

## What it is

Pathfinder is an experimental native Android adaptation of
[pi](https://pi.dev). Most of the codebase ports the parts of pi's AI and agent
runtime that the app needs to Kotlin, surrounded by a thin Android shell built
with Jetpack Compose and Material 3.

Pi is the source of truth for runtime behavior; Android is the source of truth
for the mobile experience. Pathfinder is not an attempt to bring all of pi—or
its terminal UI—to Android.

I explored building on existing Kotlin agent frameworks, including JetBrains'
[Koog](https://github.com/JetBrains/koog). A selective port of pi ultimately
proved simpler for this project, gives me the behavior I want, and leaves more
room to experiment.

## Built with agents

Pathfinder is also an experiment in agent-driven software development. I set
the direction and decide what is worth building; orchestrator agents split the
work across parallel worktrees and larger swarms, then review and merge the
results.

A faithful port is a useful test bed for this: upstream source and tests give
agents a concrete target, while the native Android interface still leaves room
for product and design work.

Detailed porting scope and implementation guidance live in
[AGENTS.md](AGENTS.md).

## Build

Install the JDK and Android SDK versions declared by the Gradle configuration,
set `ANDROID_HOME`, then run:

```bash
./gradlew spotlessCheck test assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
