# SF Toolkit

Salesforce toolkit plugin for JetBrains IDEs (IntelliJ IDEA CE/Ultimate, and any
other IDE on the IntelliJ platform). All Salesforce interaction is delegated to
the [Salesforce CLI](https://developer.salesforce.com/tools/salesforcecli) —
the plugin stores no credentials; `sf` CLI auth is the single source of truth.

## Features (roadmap)

- Org picker (status bar) backed by `sf org list` / `sf org login web`
- Retrieve / Deploy / Validate / Compare-with-org for SFDX source-format projects
- Apex/SOQL syntax highlighting + Apex Language Server integration
- SOQL query tool window
- Anonymous Apex tool window
- Apex test runner

## Requirements

- A JetBrains IDE, 2024.2 or newer
- Salesforce CLI (`sf`) on PATH, already authenticated to your org(s)
- To build: JDK 21 (LTS) — `JAVA_HOME` must point at it

## Build & install

```sh
./gradlew buildPlugin
```

Then in the IDE: Settings → Plugins → ⚙ → Install Plugin from Disk… →
`build/distributions/sf-toolkit-<version>.zip`. No Marketplace, no signing.

For development: `./gradlew runIde` launches a sandboxed IDE with the plugin.
