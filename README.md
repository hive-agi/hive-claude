# hive-claude

<!-- hive-badges -->

[![Clojars Project](https://img.shields.io/clojars/v/io.github.hive-agi/hive-claude.svg)](https://clojars.org/io.github.hive-agi/hive-claude)
[![cljdoc](https://cljdoc.org/badge/io.github.hive-agi/hive-claude)](https://cljdoc.org/d/io.github.hive-agi/hive-claude/CURRENT)
[![release](https://github.com/hive-agi/hive-claude/actions/workflows/release.yml/badge.svg)](https://github.com/hive-agi/hive-claude/actions/workflows/release.yml)
[![License: EPL-2.0](https://img.shields.io/badge/License-EPL--2.0-blue.svg)](https://www.eclipse.org/legal/epl-2.0/)

<!-- /hive-badges -->

**Claude Code as a hive backend.** It exposes Claude Code through the hive
addon ports as a headless backend, and names no editor: it loads, runs and
reports healthy with no Emacs, Vim or other vessel present.

## Coordinates

```clojure
;; deps.edn
io.github.hive-agi/hive-claude {:mvn/version "0.1.11"}
```

## Two backends, one addon

`hive-claude.init` is the `IAddon` implementation — a `reify` over a nil-railway
pipeline, with **zero compile-time `hive-mcp` dependencies**; everything
host-side resolves through `requiring-resolve`.

**Process.** `hive-claude.headless.process-backend` registers the
`:claude-process` headless backend: `claude` driven through a ProcessBuilder.

**Terminal sessions belong to the vessel.** A Claude Code session inside an
editor buffer (the `:claude` terminal) is contributed by the vessel that owns
that editor. hive-emacs registers it from `hive-emacs.claude-terminal`, with the
`hive-claude-*` elisp package in its own `elisp/` tree. Another vessel adds its
own terminal the same way; hive-claude does not change.

**Agent SDK.** `hive-claude.sdk.agentic-loop` implements `IAgenticLoop` over the
Claude Agent SDK:

- async start/abort lifecycle via `sdk.lifecycle` spawn/kill
- `collect-response!` with a `CompletableFuture` and a timeout
- cost tracking accumulated from SAA completion events
- transcript access from session observations
- mid-session constraints through budget guardrails

## Layout

| Path | Contents |
|---|---|
| `src/clj/hive_claude/` | Addon init, guard projection |
| `src/clj/hive_claude/headless/` | `:claude-process` headless backend |
| `src/clj/hive_claude/sdk/` | Agent SDK lifecycle, execution, event loop, options, availability, SAA, phase compression |

The Python-side Agent SDK dependencies (`libpython-clj`) are loaded lazily
through `requiring-resolve`, so the process backend works on a JVM that has no
Python environment at all.

## License

AGPL-3.0-or-later.
