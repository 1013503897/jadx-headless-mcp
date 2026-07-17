# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

A headless MCP (stdio) server for Android APK static analysis, written in Kotlin on top of the `jadx-core` library. Distinguishing trait vs. existing JADX MCP projects: **no JADX GUI / no plugin / no Python adapter** — one JVM process speaking MCP over stdio. The server exposes ~20 tools (`load_apk`, `get_class_source`, `get_xrefs_to_method`, etc.) and is registered once in MCP client config; APKs are swapped at runtime via the `load_apk` / `unload_apk` tools rather than by editing config.

Requires JDK 17+. Single Gradle module, no submodules.

Local checkout: `C:/work/git_code/hook-lab/jadx-headless-mcp`.  
MCP name `jadx-headless` → launcher `build/install/jadx-headless-mcp/bin/jadx-headless-mcp.bat` (Claude `~/.claude.json` / Grok `~/.grok/config.toml`).

## Build & run

```bash
./gradlew installDist
# launcher: build/install/jadx-headless-mcp/bin/jadx-headless-mcp[.bat]

./gradlew shadowJar
# fat jar: build/libs/jadx-headless-mcp-<version>-all.jar

./gradlew run          # runs main with stdin wired through (standardInput = System.in)
./gradlew compileKotlin # quickest type-check

# After installDist, smoke-test the launcher:
./build/install/jadx-headless-mcp/bin/jadx-headless-mcp --help
```

No test sources exist yet (`src/main/kotlin` only). CI (`.github/workflows/build.yml`) runs `installDist` + `--help` smoke check on ubuntu-latest.

## Architecture

```
stdin/stdout (MCP JSON-RPC)
        │
   Main.kt ── registers ~20 tools on a Server, then StdioServerTransport
        │
   SessionHolder ── Mutex-serialized load() / unload(); holds 0..1 JadxSession
        │
   JadxSession ── thin wrapper over jadx.api.JadxDecompiler
                   • lazy `classes`, `resources`, FQN→class map, name→methods map
                   • truncate(maxSourceBytes) applied in getClassSource/getSmali
                   • decompilation is lazy: only get_class_source / get_smali_of_class
                     trigger per-class work; startup builds metadata indexes only
```

Three Kotlin files total; keep changes scoped to whichever layer owns the concern:

- `Main.kt` — arg parsing, MCP tool registration & input-schema definitions, JSON result helpers, AndroidManifest/strings parsing (regex-based, intentionally not a full XML parser), resource rendering. Adding a new MCP tool = adding a `server.addTool(...)` block here.
- `SessionHolder.kt` — thread-safe single-slot lifecycle (`load`, `unload`, `snapshot`, `current`). Always go through this rather than holding a `JadxSession` reference elsewhere.
- `JadxSession.kt` — all direct contact with `jadx.api.*`. New static-analysis primitives (finding nodes, describing xrefs, etc.) belong here so `Main.kt` stays a thin tool-dispatcher.

### Critical conventions

- **stdout is reserved for MCP JSON-RPC frames.** `Main.kt` reroutes `System.out` to `System.err` early in `main()` because some logging libraries print banners to stdout that would corrupt the protocol. Never `println` from anywhere; use `System.err` or SLF4J (configured to log to stderr via `slf4jSimpleLogger.logFile=System.err` in `applicationDefaultJvmArgs`).
- **`maxSourceBytes` truncation** is enforced both in `JadxSession.truncate` (for class source/smali) and in `Main.renderResource` / `truncate` (for resource files). Configurable via `--max-source-bytes` (default 60_000 in Main). **Truncation runs after jadx finishes** — it does not abort a hung decompile.
- **`decompileTimeoutMs` hard wall-clock budget** (default **90_000**, CLI `--decompile-timeout-ms`) wraps every full-class materialization (`get_class_source`, `get_smali_of_class`, method source, summary, code-search). Without this, control-flow-obfuscated GCash classes could block the single MCP process until the client multi-hour tool ceiling (looked like “jadx hung for 1–2 hours”). On timeout the tool returns an `// ERROR: ... timed out ...` banner immediately. Code-search per-class is further capped at 20s.

- **Tool result shape:** use `okJson(buildJsonObject { … })`, `textResult(text)`, or `errorResult(msg)`. For "no APK loaded" the helper is `noApkLoaded()`. Tool error returns set `isError = true` rather than throwing.
- **One APK per process.** `SessionHolder.load` closes the previous session before opening a new one; concurrency around this is enforced by a `Mutex`. Callers must not retain references to a previous `JadxSession` across a `load_apk` call.

## Versioning & upstream

- `build.gradle.kts` pins `jadxVersion`, `mcpKotlinSdkVersion`, `slf4jVersion` as `val`s at the top — bump there.
- Dependabot (`.github/dependabot.yml`) groups updates as `jadx`, `mcp-kotlin-sdk`, `kotlinx`; CI must pass on the resulting PR.
- Project version (string in `serverInfo = Implementation(... version = "0.2.0")` inside `Main.kt`) and Gradle `version` in `build.gradle.kts` are tracked separately — update both when releasing.
