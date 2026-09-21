# ViaPluginUpdater

> tired of updating viaplugins almost everyday, this simple plugin is for you

A lightweight Paper and Velocity plugin that automatically checks, validates, and stages selected plugin updates.

## Supported plugins

- ViaVersion
- ViaBackwards
- ViaRewind
- ProtocolLib
- PacketEvents
- SkinsRestorer
- Floodgate (Spigot/Paper build)

The separate Velocity build updates only:

- SkinsRestorer
- Floodgate (Velocity build)

Each plugin can independently follow stable or development builds from its official distribution channel.

## Features

- Automatic checks on a configurable schedule
- Per-plugin `release` or `dev` update channel
- Downloads only from official GitHub, ViaVersion CI, CodeMC CI, and GeyserMC Downloads API hosts
- Validates file size, SHA-256 when published, JAR structure, plugin name, and version
- Stages updates in Paper's `plugins/update` folder for the next full restart
- Atomically replaces the installed JAR on Velocity; the new version loads after a proxy restart
- Detects new dev builds even when the `SNAPSHOT` version string does not change
- Optionally removes superseded JAR copies after the new version is loaded
- Never hot-reloads protocol plugins

## Requirements

- Bukkit, Spigot, or Paper from Minecraft 1.7.10 through 26.2
- Velocity 3.4 or newer for the Velocity build
- Java 8 or newer for ViaPluginUpdater itself

Current standard builds of the Via plugins normally require Java 17 or newer. On a Java 8 server,
use the official ViaVersion Java 8 compatibility builds. ViaVersion itself does not officially run
directly on a 1.7.10 Bukkit server; that server version may require a supported proxy or ViaLegacy.

## Installation

1. Download the `paper` or `velocity` JAR from this branch's build output.
2. Put it in the server or proxy `plugins` folder.
3. Start or restart the server.
4. On Paper, edit `plugins/PluginUpdater/config.yml` if needed. On Velocity, edit
   `plugins/pluginupdater/velocity.properties`.

Do not use `/reload` or a plugin manager to apply Via plugin updates. Use a full server restart.

## Update channels

Set `channel: release` for stable GitHub releases or `channel: dev` for the latest official development build. ViaVersion, ViaBackwards, and ViaRewind use ViaVersion CI; ProtocolLib uses its `dev-build` GitHub release; PacketEvents and SkinsRestorer use CodeMC CI. Floodgate uses the official GeyserMC Downloads API. On Velocity, `skinsrestorer-channel` is configurable and Floodgate always follows the latest official build.

Network failures are logged as a single concise warning by default. Set
`show-stack-traces: true` only when a complete exception trace is needed for debugging.

## Paper commands

| Command | Description |
| --- | --- |
| `/pluginupdater status` | Shows installed, latest, and staged versions |
| `/pluginupdater check` | Checks without downloading |
| `/pluginupdater update` | Checks and immediately stages available updates |
| `/pluginupdater reload` | Reloads the configuration |

All commands require `pluginupdater.admin`, granted to server operators by default.
The Velocity build runs automatically and currently has no commands.

## Building

```shell
mvn clean package
```

The build creates `target/PluginUpdater-1.4.0-extended-paper.jar` and
`target/PluginUpdater-1.4.0-extended-velocity.jar`.

## Disclaimer

This project is not affiliated with or endorsed by ViaVersion, ProtocolLib, PacketEvents, SkinsRestorer, or GeyserMC. All supported plugins belong to their respective authors.
