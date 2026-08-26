# HTTP Request Mod

A server-side Fabric mod for HTTP requests and an opt-in bridge between Minecraft chat and a live [pi coding-agent](https://github.com/badlogic/pi-mono) session.

The pi bridge uses this flow:

> `/pi` in Minecraft → persistent WebSocket → companion pi extension → active coding-agent session → streamed Minecraft chat output

> [!WARNING]
> A connected coding agent may execute commands and modify or delete files with the permissions of the pi process. Only dedicated-server operators or the integrated-server owner can configure the bridge or use `/pi`. Run pi in a project you trust, keep the endpoint on loopback unless remote access is deliberately secured, and review the separately maintained companion extension before installing it.

## Requirements and installation

1. Install [Fabric Loader](https://fabricmc.net/use/server/) for Minecraft 1.21.10.
2. Install the [Fabric API](https://modrinth.com/mod/fabric-api).
3. Download the mod JAR from the [latest GitHub release](https://github.com/cmoyates/HTTP-Request-Mod/releases/latest).
4. Put the mod JAR and Fabric API JAR in the logical server's `mods` directory.

For a dedicated server, install the mod on the server only. For single-player, the logical server is the integrated server, so install the mod and Fabric API in the Minecraft client's `mods` directory.

## Quick start with pi

This repository ships only the Fabric mod. The companion pi extension belongs in a separate repository and must implement [WebSocket protocol v1](docs/websocket-protocol-v1.md). See [How to build the companion pi extension](docs/building-pi-extension.md) for its repository layout, pi lifecycle integration, security requirements, and test plan.

Install and start that extension in the coding project Minecraft should control. With the recommended defaults it listens on `ws://127.0.0.1:8765/`. In Minecraft, a dedicated-server operator or the single-player world owner can connect and send a prompt:

```mcfunction
/websocket connect
/pi Inspect this project and summarize it
```

Ordinary Minecraft chat is never forwarded and continues to work normally.

To connect automatically on later world/server starts:

```mcfunction
/websocket autoconnect true
```

Automatic connection is intentionally opt-in. Enabling it affects future world starts; run `/websocket connect` as well if a world is already open.

See [How to build the companion pi extension](docs/building-pi-extension.md) before creating or installing the separate companion package.

## WebSocket configuration

The mod creates `http-request-mod.json` in Fabric's config directory (`config/` for a dedicated server, normally `.minecraft/config/` for single-player):

```json
{
  "endpoint": "ws://127.0.0.1:8765",
  "automaticConnect": false,
  "sharedToken": "",
  "promptTimeoutSeconds": 120
}
```

| Field | Meaning |
| --- | --- |
| `endpoint` | Persisted absolute `ws://` or `wss://` endpoint. Defaults to loopback. |
| `automaticConnect` | If `true`, connect asynchronously at `SERVER_STARTED` for either an integrated or dedicated server. |
| `sharedToken` | Optional bearer token sent only in the WebSocket upgrade. Required for non-loopback endpoints. Never shown by status commands or logs. |
| `promptTimeoutSeconds` | Total prompt timeout from 10 through 1800 seconds. Defaults to 120. |

The file is written atomically and uses owner-only permissions where the file system supports POSIX permissions. Protect it because it may contain a token. A malformed, invalid, missing, or unavailable endpoint never blocks world startup. Malformed configuration leaves safe in-memory defaults or the last known-good settings active.

Non-loopback endpoints require both `wss://` and a non-empty shared token. URI user-info and fragments are rejected, and query values are redacted from all endpoint displays. Do not put credentials in an endpoint URL.

After editing the file while a server is running, use `/websocket reload`, then reconnect.

## Commands

All commands require dedicated-server operator permission level 2 or the integrated-server owner. `/pi` additionally requires a player command source.

```mcfunction
/websocket endpoint
/websocket endpoint <ws-or-wss-url>
/websocket endpoint clear
/websocket autoconnect <true|false>
/websocket connect
/websocket connect <ws-or-wss-url>
/websocket disconnect
/websocket status
/websocket reload
/pi <prompt>
```

- `endpoint <URL>` validates and persists the selected endpoint without connecting.
- `connect` uses the persisted endpoint. `connect <URL>` persists it first for compatibility with the original command.
- An unexpected close triggers reconnect attempts after 1, 2, 4, 8, 16, then at most 30 seconds indefinitely.
- `disconnect` closes normally and suppresses reconnects for the current world/server lifetime. It does not change the persisted automatic-connect setting.
- World/server shutdown cancels reconnects and prompt timers and sends a normal WebSocket close.
- `status` reports disconnected, connecting, connected, or reconnecting state, a credential-safe endpoint, the reconnect attempt, automatic-connect state, and only whether authentication is configured.
- `/pi` creates a correlated request. Stream chunks return only to the requesting player, are coalesced and split into bounded literal chat components, and are displayed at no more than four components per second.

Connection and session diagnostics are shown only to online dedicated-server operators or the integrated-server owner. Incoming WebSocket callbacks are marshalled through the Minecraft server executor before player lookup, request mutation, component creation, or delivery.

## Wire protocol

The mod and extension use a small versioned JSON protocol with `hello`, `message`, `status`, `chunk`, `complete`, and `error` messages. Request UUIDs and monotonically increasing chunk sequences prevent response streams from being mixed. Prompts are never replayed after a disconnect because a coding turn may already have performed side effects.

See the normative [WebSocket protocol v1](docs/websocket-protocol-v1.md).

## Development

Run the tests and build the remapped mod JAR:

```shell
./gradlew clean build
```

The separate companion extension should have its own TypeScript tests and CI as described in the [build guide](docs/building-pi-extension.md).

## Publishing a release

Maintainers can publish a release from the repository's **Actions** tab:

1. Select the **Publish release** workflow.
2. Choose **Run workflow**.
3. Select a semantic version bump: `patch`, `minor`, or `major`.
4. Run the workflow from `main`.

The workflow updates `mod_version`, builds and tests the mod with Java 21, commits the version change, creates a matching `vX.Y.Z` tag, and publishes the mod JAR and its SHA-256 checksum to a GitHub release.
