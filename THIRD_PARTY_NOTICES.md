# Third-Party Notices

## pi

The provider/chat streaming layer (under `app/src/main/kotlin/works/resolve/pathfinder/ai/`)
and the agent loop (under `app/src/main/kotlin/works/resolve/pathfinder/agent/`) are
Kotlin ports of portions of [pi](https://pi.dev) (the local pi project at
`~/Projects/pi`). Those portions are:

Copyright (c) 2025 Mario Zechner

Licensed under the MIT License reproduced below. The attribution here applies
to the ported portions only; it does not place a license on pathfinder as a
whole.

```
MIT License

Copyright (c) 2025 Mario Zechner

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## scry

The Brave web search tool and its provider credential service
(`app/src/main/kotlin/works/resolve/pathfinder/tools/websearch/`) are Kotlin
ports of the [scry](https://github.com/resolveworks/scry) pi extension (the
local source at `~/Projects/scry`). That portion is:

Copyright (c) 2025 Johan Schuijt

Licensed under the MIT License reproduced above. The attribution here applies
to the ported portions only; it does not place a license on pathfinder as a
whole.

## defuddle

The `web_fetch` tool injects the browser bundle of
[defuddle](https://github.com/kepano/defuddle) (npm release `defuddle@0.19.3`,
file `dist/index.full.js`) into pages rendered by its hidden WebView to extract
article content and metadata. The bundled file is:

Copyright (c) 2025 Steph Ango (@kepano)

Licensed under the MIT License reproduced above.

The bundled file additionally compiles in the following MIT-licensed libraries:

- turndown — Copyright (c) 2017 Dom Christie
- mathml-to-latex — Copyright (c) 2020 Alexandre Nunes
- temml — Copyright (c) 2020 Ron Kok

Each is licensed under the MIT License reproduced above.
