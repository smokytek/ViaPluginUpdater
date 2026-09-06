# ViaPluginUpdater

> tired of updating viaplugins almost everyday, this simple plugin is for you

A lightweight Bukkit, Spigot, and Paper plugin that automatically checks, validates, and stages protocol-plugin updates.

## Supported plugins

- ViaVersion
- ViaBackwards
- ViaRewind
- ProtocolLib
- PacketEvents

Each plugin can independently follow stable or development builds from its official distribution channel.

## Features

- Automatic checks on a configurable schedule
- Per-plugin `release` or `dev` update channel
- Downloads only from official GitHub, ViaVersion CI, and CodeMC CI hosts
- Validates file size, SHA-256 when published, JAR structure, plugin name, and version
- Stages updates in Paper's `plugins/update` folder for the next full restart
- Detects new dev builds even when the `SNAPSHOT` version string does not change
- Optionally removes superseded JAR copies after the new version is loaded
- Never hot-reloads protocol plugins

## Requirements

- Bukkit, Spigot, or Paper from Minecraft 1.7.10 through 26.2
- Java 8 or newer for ViaPluginUpdater itself

Current standard builds of the Via plugins normally require Java 17 or newer. On a Java 8 server,
use the official ViaVersion Java 8 compatibility builds. ViaVersion itself does not officially run
directly on a 1.7.10 Bukkit server; that server version may require a supported proxy or ViaLegacy.

## Installation

1. Download `PluginUpdater-1.2.0-extended.jar` from this branch's build output.
2. Put it in the server's `plugins` folder.
3. Start or restart the server.
4. Edit `plugins/PluginUpdater/config.yml` if needed.

Do not use `/reload` or a plugin manager to apply Via plugin updates. Use a full server restart.

## Update channels

Set `channel: release` for stable GitHub releases or `channel: dev` for the latest official development build. ViaVersion, ViaBackwards, and ViaRewind use ViaVersion CI; ProtocolLib uses its `dev-build` GitHub release; PacketEvents uses CodeMC CI. The channel is configured independently for every plugin.

Network failures are logged as a single concise warning by default. Set
`show-stack-traces: true` only when a complete exception trace is needed for debugging.

## Commands

| Command | Description |
| --- | --- |
| `/pluginupdater status` | Shows installed, latest, and staged versions |
| `/pluginupdater check` | Checks without downloading |
| `/pluginupdater update` | Checks and immediately stages available updates |
| `/pluginupdater reload` | Reloads the configuration |

All commands require `pluginupdater.admin`, granted to server operators by default.

## Building

```shell
mvn clean package
```

The compiled plugin is written to `target/PluginUpdater-1.2.0-extended.jar`.

## Disclaimer

This project is not affiliated with or endorsed by ViaVersion, ProtocolLib, or PacketEvents. All supported plugins belong to their respective authors.
