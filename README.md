# HTTP Request Mod

A server-side Fabric mod for making HTTP requests and forwarding Minecraft chat messages to a WebSocket endpoint.

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/server/) for Minecraft 1.21.10.
2. Install the [Fabric API](https://modrinth.com/mod/fabric-api).
3. Download the mod JAR from the [latest GitHub release](https://github.com/cmoyates/HTTP-Request-Mod-Rebuild/releases/latest).
4. Place the mod JAR and Fabric API JAR in the server's `mods` directory.

The mod only needs to be installed on the server.

## WebSocket chat forwarding

WebSocket commands can be run from the server console or by a player with operator permission (permission level 2 or higher).

### Connect

```mcfunction
/websocket connect <URL>
```

The URL must be an absolute `ws://` or `wss://` URL. For example:

```mcfunction
/websocket connect ws://localhost:8080/chat
/websocket connect wss://example.com/minecraft-chat
```

Only one WebSocket connection can be active at a time. Connecting to another URL replaces the current connection.

Once connected, each accepted player chat message is sent to the endpoint as an individual WebSocket text message. The payload is the raw chat message text; it does not include a JSON wrapper or the player's name. Incoming WebSocket messages are ignored.

### Check the connection

```mcfunction
/websocket status
```

This reports whether the mod is connected, connecting, or disconnected, along with the endpoint when applicable.

### Disconnect

```mcfunction
/websocket disconnect
```

The connection is also closed automatically when the server stops. Connections are not restored automatically after a server restart.
