# hive-claude

<!-- hive-badges -->

[![Clojars Project](https://img.shields.io/clojars/v/io.github.hive-agi/hive-claude.svg)](https://clojars.org/io.github.hive-agi/hive-claude)
[![cljdoc](https://cljdoc.org/badge/io.github.hive-agi/hive-claude)](https://cljdoc.org/d/io.github.hive-agi/hive-claude/CURRENT)
[![release](https://github.com/hive-agi/hive-claude/actions/workflows/release.yml/badge.svg)](https://github.com/hive-agi/hive-claude/actions/workflows/release.yml)
[![License: EPL-2.0](https://img.shields.io/badge/License-EPL--2.0-blue.svg)](https://www.eclipse.org/legal/epl-2.0/)

<!-- /hive-badges -->

**Claude Code as a hive backend — both the terminal you watch and the headless
loop you don't.** It exposes Claude Code through the hive addon ports, so the
same host code can drive an interactive Emacs terminal session or a fully
headless Agent SDK run.

## Coordinates

```clojure
;; deps.edn
io.github.hive-agi/hive-claude {:mvn/version "0.1.11"}
```

## Two backends, one addon

`hive-claude.init` is the `IAddon` implementation — a `reify` over a nil-railway
pipeline, with **zero compile-time `hive-mcp` dependencies**; everything
host-side resolves through `requiring-resolve`.

**Terminal.** `hive-claude.terminal` implements `ITerminalAddon`, bridging
JVM-side ling management to the Emacs-side `hive-claude` elisp package by
evaluating elisp through the Emacs client. The elisp is not hand-written: it is
compiled from ClojureElisp sources under `src/cljel/`.

**Headless.** `hive-claude.sdk.agentic-loop` implements `IAgenticLoop` over the
Claude Agent SDK:

- async start/abort lifecycle via `sdk.lifecycle` spawn/kill
- `collect-response!` with a `CompletableFuture` and a timeout
- cost tracking accumulated from SAA completion events
- transcript access from session observations
- mid-session constraints through budget guardrails

## Layout

| Path | Contents |
|---|---|
| `src/cljel/hive_claude/` | ClojureElisp sources — bridge, config, state, sync |
| `src/clj/hive_claude/` | Terminal adapter, elisp emission, addon init |
| `src/clj/hive_claude/sdk/` | Agent SDK lifecycle, execution, event loop, options, availability, SAA, phase compression |

The Python-side Agent SDK dependencies (`libpython-clj`) are loaded lazily
through `requiring-resolve`, so the terminal backend works on a JVM that has no
Python environment at all.

## License

AGPL-3.0-or-later.
