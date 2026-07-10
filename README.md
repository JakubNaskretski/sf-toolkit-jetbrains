# SF Toolkit

Salesforce toolkit plugin for JetBrains IDEs (IntelliJ IDEA CE/Ultimate, and any
other IDE on the IntelliJ platform, 2024.2+). All Salesforce interaction is
delegated to the [Salesforce CLI](https://developer.salesforce.com/tools/salesforcecli) —
the plugin stores no credentials; `sf` CLI auth is the single source of truth.

## Install (no build needed)

1. Go to this repo's **Releases** page and download `sf-toolkit-<version>.zip`
   from the latest release's **Assets**.
2. In the IDE: **Settings → Plugins → ⚙ → Install Plugin from Disk…** → pick the
   ZIP → restart.

Requirements on the machine running the IDE:

- Salesforce CLI (`sf`) on PATH and authenticated to your org(s)
  (or set its full path in **Settings → Tools → SF Toolkit**)
- Optional, for Apex language features: the free **LSP4IJ** plugin from the
  Marketplace, plus the Salesforce VS Code Apex extension installed (the plugin
  auto-detects its `apex-jorje-lsp.jar`; a custom path can be set in settings)

## Features

- **Org picker** in the status bar (backed by `sf org list` / `sf org login web`),
  plus an org switcher inside the SOQL window
- **Retrieve / Deploy / Validate (dry run)** right-click actions for SFDX
  source-format projects
- **Compare with Org** — diffs the local Apex class/trigger/VF page/component
  against the org version in the IDE's native diff viewer
- **Apex/SOQL file types + syntax highlighting** — `.cls`/`.trigger`/`.apex`/`.soql`
  get real file types (icons, Settings → File Types entry) with highlighting
  delegated to the TextMate grammars (forcedotcom/apex-tmLanguage, BSD-3-Clause)
- **Apex Language Server** integration through LSP4IJ: completion,
  go-to-definition, hover, references, diagnostics
- **SOQL tool window** — cancellable queries, auto-LIMIT toggle, flattened
  result table with sorting and speed search, and **schema-aware completion**
  (Ctrl+Space): sync the org's object list once, field completion learns each
  object automatically the first time you query it (offline cache, per org)
- **Anonymous Apex tool window** — run snippets against the org; compile errors
  with line/column, runtime exceptions with stack trace, full debug log
- **Apex test runner** — right-click a test class → Run Apex Tests in Class;
  pass/fail summary with per-failure messages and stack traces in the SF Log

- **Search scopes** — "SF: Apex", "SF: SOQL", "SF: Metadata XML" and
  "SF: All Salesforce Source" appear in the Scope dropdown of Find in Files,
  usages, inspections and the TODO view (plain file masks like `*.cls` work
  natively in the File mask box)

Every CLI call is logged (queries redacted) to the **SF Log** tool window.

## Build from source (only for development)

```sh
./gradlew buildPlugin        # macOS/Linux — note the ./
.\gradlew buildPlugin        # Windows PowerShell
```

Requires a JDK 21 (`JAVA_HOME` must point at it); Gradle itself is NOT needed —
the committed wrapper downloads it. Output lands in `build/distributions/`.
`./gradlew runIde` launches a sandboxed IDE with the plugin for testing.
