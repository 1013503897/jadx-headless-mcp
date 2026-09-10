---
name: open-apk
description: Load an Android APK / XAPK / DEX / JAR into the jadx-headless MCP for static analysis. Accepts a local file path, or a package name to fetch from APKPure via apkeep. Use when the user asks to open/load an APK in jadx (e.g. "open com.example.app in jadx", "load ./demo.xapk", "/jadx-headless:open-apk <path|package>").
---

# Open APK in jadx-headless

Load a target into the `jadx-headless` MCP server so its decompilation / search / xref tools
become usable, then report a one-line summary.

## Input

A single argument `$ARGUMENTS`:

- Looks like a file — ends in `.apk` / `.xapk` / `.dex` / `.jar`, contains a path separator, or
  starts with `./` `../` `~` `/` or a Windows drive letter → **path mode**.
- Otherwise → **package-name mode** (fetch the APK first).

## Steps

1. **Check current state.** Call the `status` tool.
   - `EMPTY` → go to step 2.
   - `LOADED` → call `get_app_info`; if its `package` already matches the target, skip to step 4
     (already loaded). Otherwise tell the user you will switch (loading a new APK replaces the old
     session), then continue.
   - The call itself fails → the MCP server is not running; tell the user to check that the
     `jadx-headless` plugin is enabled (and that `java` 17+ is on PATH), then stop.

2. **Obtain the file.**
   - Path mode: verify the file exists (fail with a clear message if not), use its absolute path.
   - Package-name mode: download from APKPure with [apkeep](https://github.com/EFForg/apkeep)
     (a public CLI the user must have installed):
     ```bash
     mkdir -p ./apks && apkeep -a <package> -d apk-pure ./apks/
     ```
     If apkeep is missing or the download fails, tell the user and ask for a local path instead.

3. **Load.** Call `load_apk(path="<absolute path>")`. It blocks until indexing finishes and returns
   `{state, class_count, resource_count, load_duration_ms}`.

4. **Report.** Call `get_app_info` and print one line:
   `<package>@<versionName> — <class_count> classes, <resource_count> resources`.

## Handy follow-up tools

| Goal | Tool |
|---|---|
| List / filter classes | `list_classes(prefix="com.example", limit=200)` |
| Keyword class search | `search_classes_by_keyword(search_term="Revenue", search_in="class")` |
| Decompiled source | `get_class_source(class_name="…")` |
| Who calls this method | `get_xrefs_to_method(...)` |
| AndroidManifest | `get_android_manifest()` |

All tools are provided by the `jadx-headless` MCP server bundled with this plugin.
