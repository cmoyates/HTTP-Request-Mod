# How to build the companion pi extension

This repository intentionally ships **only the Fabric mod**. Build and publish the pi companion as a separate repository and package. The companion is a WebSocket server that translates the mod's versioned protocol into one turn in the active pi coding-agent session, then streams assistant text back to Minecraft.

Use [WebSocket protocol v1](websocket-protocol-v1.md) as the normative wire contract. This guide describes the recommended implementation and the security and lifecycle properties that must not be omitted.

> [!WARNING]
> A pi extension runs with all permissions of the pi process. A connected Minecraft operator can cause the agent to run tools and modify files. Keep the listener on loopback by default, authenticate remote clients, use TLS remotely, and install the extension only in trusted coding projects.

## 1. Create a separate repository

A small TypeScript package is sufficient:

```text
minecraft-pi-extension/
├── src/
│   ├── index.ts          # pi lifecycle/event adapter
│   ├── protocol.ts       # protocol-v1 validation and serialization
│   └── server.ts         # authenticated WebSocket bridge
├── test/
│   ├── index.test.ts
│   ├── protocol.test.ts
│   └── server.test.ts
├── package.json
├── package-lock.json
├── tsconfig.json
├── README.md
└── .github/workflows/ci.yml
```

The implementation developed with this mod targeted `@earendil-works/pi-coding-agent` 0.84.3 and Node.js 22.19 or newer. Check current pi extension documentation before publishing and pin the version used by CI.

A starting `package.json` is:

```json
{
  "name": "your-minecraft-pi-extension",
  "version": "0.1.0",
  "private": true,
  "type": "module",
  "engines": { "node": ">=22.19.0" },
  "keywords": ["pi-package", "minecraft", "websocket"],
  "pi": { "extensions": ["./src/index.ts"] },
  "scripts": {
    "typecheck": "tsc --noEmit",
    "test": "tsx --test test/*.test.ts"
  },
  "dependencies": { "ws": "8.21.3" },
  "peerDependencies": {
    "@earendil-works/pi-coding-agent": "*"
  },
  "devDependencies": {
    "@earendil-works/pi-coding-agent": "0.84.3",
    "@types/node": "22.18.1",
    "@types/ws": "8.18.1",
    "tsx": "4.20.5",
    "typescript": "5.9.2"
  }
}
```

Use strict `NodeNext` TypeScript settings and commit the lockfile. Revisit pinned versions when the extension repository is created; the values above document the tested baseline rather than an evergreen dependency recommendation.

## 2. Implement the protocol module

`protocol.ts` should be the only place that understands wire shapes. Implement discriminated types and runtime validation for:

- client `hello` and `message` frames;
- server `status`, `chunk`, `complete`, and `error` frames;
- protocol version `1`;
- UUID request and player IDs;
- exact client name `http-request-mod`;
- nonblank prompts of at most 4,096 Unicode code points;
- UTF-8 application frames of at most 65,536 bytes;
- assistant chunks of at most 4,096 Unicode code points.

Serialize all outbound objects through one size-checking function. Split text by Unicode code point (`Array.from(text)`), not UTF-16 index, so surrogate pairs are never broken. Sanitize diagnostic strings to one line and enforce the limits in the protocol document.

Unknown object fields may be ignored, but reject unknown message types, malformed JSON, invalid UUIDs, binary frames, and unsupported versions with stable protocol error codes. Do not trust player metadata for authorization; the Fabric mod performs command authorization, while the WebSocket upgrade authenticates the peer.

## 3. Implement the WebSocket server

`server.ts` should expose a bridge class with methods similar to:

```ts
start(): Promise<void>
stop(): Promise<void>
status(): BridgeStatus
onExtensionInput(text: string): void
onUserMessageStart(text: string): void
onAssistantTextDelta(delta: string): void
onAssistantMessageEnd(stopReason: string): void
onAgentSettled(): void
```

Inject callbacks instead of importing pi directly:

```ts
interface BridgeCallbacks {
  isSessionAvailable(): boolean;
  submitPrompt(content: string): void;
  abortPrompt(): void;
  onStatusChanged(status: BridgeStatus): void;
}
```

This keeps the transport testable with a fake pi session.

### Listener and authentication

Use `ws` with `perMessageDeflate: false` and `maxPayload: 65_536`. Create an HTTP server for loopback development or an HTTPS server when certificate and key files are supplied.

Requirements:

1. Bind to `127.0.0.1` by default.
2. Return 404 for ordinary HTTP requests and accept upgrades only on the exact configured path.
3. If a token is configured, require exactly `Authorization: Bearer <token>` and compare equal-length values with `crypto.timingSafeEqual`.
4. Refuse a non-loopback bind unless a non-empty token **and** TLS certificate/private key are configured.
5. Never put the token in a URL, application frame, status value, exception, or log.
6. Allow only one authenticated Minecraft connection at a time.
7. Require `hello` within five seconds.
8. Ping every 20 seconds and terminate a connection that fails to pong.
9. Reject binary or oversized messages before parsing them.
10. Bound WebSocket send buffering; terminate a peer that exceeds the configured backpressure threshold.

Read TLS files only at startup. On any partial startup failure, close the WebSocket/HTTP server and timers so `/reload` or a later session can bind successfully.

### Configuration

Read configuration from the pi process environment when the extension starts:

| Variable | Default | Meaning |
| --- | --- | --- |
| `PI_MINECRAFT_WS_HOST` | `127.0.0.1` | Listener bind host. |
| `PI_MINECRAFT_WS_PORT` | `8765` | Listener port, 1–65535. |
| `PI_MINECRAFT_WS_PATH` | `/` | Exact WebSocket path. |
| `PI_MINECRAFT_WS_TOKEN` | empty | Optional bearer token; mandatory remotely. |
| `PI_MINECRAFT_WS_TLS_CERT` | unset | PEM certificate chain; mandatory remotely. |
| `PI_MINECRAFT_WS_TLS_KEY` | unset | PEM private key; mandatory remotely. |
| `PI_MINECRAFT_WS_REQUEST_TIMEOUT_SECONDS` | `120` | Turn timeout, from 10 through 1800 seconds. |

Validate the entire snapshot before listening. Report credential-safe error codes to the pi UI rather than interpolating raw configuration values.

## 4. Correlate one Minecraft request to one pi turn

Pi event streams do not carry the mod's request UUID, so correlation must fail closed. Do not assume that the next assistant output belongs to Minecraft.

For each accepted `message`:

1. Reject a replayed request UUID using a bounded recent-ID set.
2. Require the pi context to be idle, with no pending messages and no active Minecraft-owned turn; otherwise send retryable `busy`.
3. Append a private, random marker to the submitted content, for example:

   ```text
   <player content>

   <!-- http-request-mod:<random UUID> -->
   ```

4. Call `pi.sendUserMessage(markedContent)`.
5. Observe an `input` event whose `source` is `extension` and whose text exactly matches `markedContent`.
6. Observe the following user `message_start` and require its text to exactly match `markedContent`.
7. Require steps 5 and 6 within a short acceptance deadline (the tested implementation used 10 seconds).
8. Do not forward assistant deltas until both checks pass.

The random marker prevents an unrelated interactive prompt with the same visible text from stealing ownership. It must be generated by the extension, must not be returned over the WebSocket, and must not contain player metadata.

If another user message starts while the request owns the turn, send `session_interleaved`, release ownership immediately, and never forward later output under that request ID. If submission is rejected, handled by another extension, or never reaches `message_start`, abort/release on the acceptance deadline. Every error, timeout, disconnect, shutdown, and interleaving path must clear both timers and active ownership so the next request is not wedged.

## 5. Stream only safe assistant text

After ownership is proven:

- listen only for assistant `message_update` events with `text_delta`;
- do not relay thinking, tool names, tool arguments, tool results, prompts, or provider error details;
- split each delta into protocol-sized chunks;
- assign sequences `0, 1, 2, ...` in send order;
- cap total response text at 262,144 UTF-16 code units;
- emit `complete` only after `agent_settled`, using the next sequence number;
- treat unsuccessful assistant stop reasons as a credential-safe request error;
- on timeout, send one terminal error, call `ctx.abort()`, and release ownership.

A WebSocket disconnect must not replay a prompt after reconnect: the agent may already have performed side effects. Record the request ID as consumed before submitting it.

## 6. Connect the bridge to pi

The default extension entry point receives `ExtensionAPI`. Keep the bridge instance scoped to the current session runtime.

Register these handlers:

| pi event | Bridge action |
| --- | --- |
| `session_start` | Start only in `tui` or `rpc` mode. Construct callbacks from the current context. |
| `input` | For `event.source === "extension"`, call `onExtensionInput(event.text)`. |
| `message_start` | For a user message, concatenate text content and call `onUserMessageStart`. |
| `message_update` | For assistant `text_delta`, call `onAssistantTextDelta`. |
| `message_end` | Pass the assistant stop reason. |
| `agent_settled` | Complete the proven request. |
| `session_shutdown` | Await bridge shutdown and clear UI status. |

The callback mapping is:

```ts
{
  isSessionAvailable: () => ctx.isIdle() && !ctx.hasPendingMessages(),
  submitPrompt: (content) => pi.sendUserMessage(content),
  abortPrompt: () => ctx.abort(),
  onStatusChanged: updateUi
}
```

Register a `/minecraft-ws [status|restart]` command. Status should expose only listener/connection/request state and the non-secret address. Restart must await complete shutdown before rebinding. Use `ctx.hasUI` before notifications or status widgets.

`/new`, `/resume`, `/fork`, `/reload`, and pi shutdown may replace the runtime. Never retain an old context or listener across `session_shutdown`.

## 7. Test before publishing

Use Node's test runner (or equivalent) plus real loopback `ws` clients. At minimum cover:

### Protocol tests

- valid Java `hello` and `message` frames;
- malformed JSON and unknown types;
- unsupported versions;
- invalid UUID/player metadata;
- empty and oversized prompts;
- multibyte frame limits and surrogate-safe chunk splitting;
- outbound frame and diagnostic sanitization limits.

### Server integration tests

- authenticated WebSocket upgrade and credential-safe 401 rejection;
- exact path, one-client policy, hello deadline, and heartbeat cleanup;
- partial bind/start failure cleanup;
- ordered chunk streaming and exact completion sequence;
- busy, duplicate request, total response limit, timeout, and disconnect behavior;
- a request that never reaches pi times out and does not wedge a later request;
- source-`extension` input plus matching marked `message_start` is required;
- an interactive prompt with the same visible text cannot claim ownership after the input gate is armed;
- interleaving releases ownership and forwards no later delta;
- all stop paths leave no listener, socket, interval, or timeout active.

### Entry-point tests

Mock `ExtensionAPI` and verify event registration, mode gating, callback mapping, UI guards, restart, and `session_shutdown` cleanup.

CI should run:

```shell
npm ci
npm run typecheck
npm test
npm audit --omit=dev --audit-level=high
```

Also load the package with the supported pi CLI before release, for example:

```shell
pi -e ./src/index.ts --list-models
```

## 8. Publish and install separately

Give the companion its own repository, versioning, releases, CI, security policy, and dependency updates. Its version does not need to match the Fabric mod; compatibility is determined by the negotiated wire protocol version.

Install it **project-locally** in the coding project Minecraft should control:

```shell
cd /path/to/coding-project
pi install -l git:github.com/YOUR-ORG/YOUR-PI-EXTENSION@vX.Y.Z
pi
```

A global install is discouraged because every interactive pi process may try to bind the same port, and Minecraft could attach to an unintended session.

For remote access, configure the environment described above, then set the mod endpoint to the matching `wss://` URL and put the same token in the mod's protected `sharedToken` field. Normal JVM certificate and hostname verification should remain enabled; do not add a trust-all mode.
