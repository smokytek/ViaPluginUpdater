# ViaPluginUpdater

> tired of updating viaplugins almost everyday, this simple plugin is for you

A lightweight Paper plugin that automatically checks, validates, and stages updates for the ViaVersion plugin family.

## Supported plugins

- ViaVersion
- ViaBackwards
- ViaRewind 

Each plugin can independently follow stable GitHub releases or successful development builds from the official ViaVersion CI.

## Features

- Automatic checks on a configurable schedule
- Per-plugin `release` or `dev` update channel
- Downloads only from official GitHub and ViaVersion CI hosts
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

1. Download `PluginUpdater-1.1.0.jar` from the latest GitHub release.
2. Put it in the server's `plugins` folder.
3. Start or restart the server.
4. Edit `plugins/PluginUpdater/config.yml` if needed.

Do not use `/reload` or a plugin manager to apply Via plugin updates. Use a full server restart.

## Update channels

Set `channel: release` for stable GitHub releases or `channel: dev` for the latest successful build from the official ViaVersion CI. The channel is configured independently for ViaVersion, ViaBackwards, and ViaRewind.

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

The compiled plugin is written to `target/PluginUpdater-1.1.0.jar`.

## Disclaimer

This project is not affiliated with or endorsed by the ViaVersion project. ViaVersion, ViaBackwards, and ViaRewind belong to their respective authors.
