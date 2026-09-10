# jadx-headless as a Claude Code plugin

This repo doubles as a [Claude Code plugin](https://code.claude.com/docs/en/plugins): the plugin
root is the repo root, so the same repository is both the MCP server source and its plugin package.

- `.claude-plugin/plugin.json` — plugin manifest (name `jadx-headless`, namespace for its skills).
- `.claude-plugin/marketplace.json` — a one-plugin marketplace so anyone can install directly from
  this repo (and so you can distribute it privately) without waiting for the community catalog.
- `.mcp.json` — registers the MCP server. Its `command` runs `scripts/Launcher.java`.
- `scripts/Launcher.java` — a self-bootstrapping launcher (see below).
- `skills/open-apk/` — a small model-invoked skill (`/jadx-headless:open-apk`).

## How the jar is delivered

The server is a ~50 MB JVM fat jar. Committing that binary into a marketplace repo is bad for a
git-based catalog and for the automated safety review, so the plugin ships as **plain text** and
the jar is fetched at runtime:

1. `.mcp.json` runs `java scripts/Launcher.java <CLAUDE_PLUGIN_DATA> <version> <releaseUrl> <sha256>`.
2. On first start the launcher downloads `jadx-headless-mcp-<version>-all.jar` from this repo's
   **GitHub Releases** into `${CLAUDE_PLUGIN_DATA}` (a per-plugin directory that survives updates),
   verifies its SHA-256 against the pinned hash, caches it, then runs it.
3. Later starts are offline and instant.

> First cold start downloads ~50 MB. If your MCP client's startup handshake times out during that
> download, run `/reload-plugins` (or start a new session) once the download has finished — the jar
> is cached from then on.

## For users

**Prerequisite: JDK 17+ on `PATH`** (`java -version` must print 17 or newer). The plugin does not
bundle a JVM.

Install once the plugin is in the community catalog:

```
/plugin marketplace add anthropics/claude-plugins-community
/plugin install jadx-headless@claude-community
```

Or install directly from this repo right now (no catalog needed):

```
/plugin marketplace add 1013503897/jadx-headless-mcp
/plugin install jadx-headless@jadx-headless-mcp
```

Then use the tools (`mcp__plugin_jadx-headless_jadx-headless__*`) or the skill
`/jadx-headless:open-apk <path|package>`.

## For the maintainer

### Cutting a release (must precede pinning the hash)

The SHA-256 must come from the exact artifact CI publishes (a local build on a different JDK
produces a different jar). So the flow is:

1. Push a tag: `git tag v0.7.0 && git push origin v0.7.0`.
2. `release.yml` builds the fat jar, uploads it plus `jadx-headless-mcp.sha256` to the GitHub
   Release, and prints the hash in the CI log (`Compute checksum` step).
3. Copy that hash into **`.mcp.json`** (the 4th arg of `Launcher.java`) and bump the URL/version if
   needed; keep **`plugin.json`** `version` and the `.mcp.json` version in sync.
4. Commit on `main`. The community catalog pins to a commit SHA and syncs nightly, so the commit
   that carries the correct hash is what users get.

Until a real 64-hex hash is pinned, the launcher runs the jar **without** integrity verification and
prints a loud warning. Do not submit to the community marketplace in that state.

The fat jar is built with reproducible file order and no timestamps
(`build.gradle.kts` → `shadowJar`), so re-running the same tag on the same JDK yields the same hash.

### Validate before submitting

```
claude plugin validate . --strict
```

This checks the manifest, field types, and that component paths stay inside the plugin.

### Submit to the community marketplace

Individual authors (not in a Team/Enterprise org) submit via the Console form:
<https://platform.claude.com/plugins/submit>. After review + automated safety screening, the plugin
is pinned into [`anthropics/claude-plugins-community`](https://github.com/anthropics/claude-plugins-community)
and installable as `jadx-headless@claude-community`. CI bumps the pin as you push new commits.

### Local development

Skip the marketplace entirely while iterating:

```
claude --plugin-dir .
```

Run `/reload-plugins` to pick up edits without restarting.
