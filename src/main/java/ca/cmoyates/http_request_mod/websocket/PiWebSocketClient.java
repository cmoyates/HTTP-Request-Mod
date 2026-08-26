package ca.cmoyates.http_request_mod.websocket;

import ca.cmoyates.http_request_mod.config.WebSocketConfigStore;
import ca.cmoyates.http_request_mod.config.WebSocketSettings;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Owns the persistent connection and all correlated Minecraft/pi requests for one running server. */
public final class PiWebSocketClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("http_request_mod/pi_websocket");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_PENDING_REQUESTS = 8;
    private static final int MAX_RESPONSE_CHARACTERS = 262_144;
    private static final int CHAT_COMPONENT_CODE_POINTS = 240;
    private static final int STREAM_FLUSH_TICKS = 4;
    private static final int CHAT_MESSAGES_PER_TICK = 1;
    private static final int CHAT_MESSAGE_INTERVAL_TICKS = 5;
    private static final int MAX_CHAT_COMPONENTS_PER_REQUEST = 1_024;
    private static final int MAX_QUEUED_OUTPUTS = 10_000;
    private static final int MAX_QUEUED_BROADCASTS = 256;
    private static final int RESERVED_OUTPUTS_PER_REQUEST = MAX_CHAT_COMPONENTS_PER_REQUEST + 2;

    private final Object connectionLock = new Object();
    private final WebSocketConfigStore configStore;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;

    // Guarded by connectionLock.
    private WebSocket connection;
    private CompletableFuture<WebSocket> connectionFuture;
    private CompletableFuture<Void> sendChain = CompletableFuture.completedFuture(null);
    private ScheduledFuture<?> reconnectFuture;
    private URI desiredEndpoint;
    private String desiredToken = "";
    private URI activeEndpoint;
    private String activeToken = "";
    private boolean desired;
    private int reconnectAttempt;
    private String lastError;
    private long generation;

    // Only accessed on the Minecraft server thread.
    private final Map<UUID, RequestState> requests = new LinkedHashMap<>();
    private final ArrayDeque<QueuedOutput> outputQueue = new ArrayDeque<>();
    private final Set<UUID> warnedPlayers = new HashSet<>();
    private int queuedBroadcasts;
    private long tickCounter;

    private volatile MinecraftServer server;
    private volatile String minecraftServerName = "minecraft";

    public PiWebSocketClient(WebSocketConfigStore configStore) {
        this.configStore = configStore;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        ThreadFactory threads = runnable -> {
            Thread thread = new Thread(runnable, "http-request-mod-websocket");
            thread.setDaemon(true);
            return thread;
        };
        this.scheduler = Executors.newSingleThreadScheduledExecutor(threads);
    }

    public void onServerStarted(MinecraftServer startedServer) {
        server = startedServer;
        minecraftServerName = truncateUtf16(startedServer.getServerMotd(), 256);
        warnedPlayers.clear();
        WebSocketSettings settings = configStore.get();
        if (!settings.automaticConnect()) {
            return;
        }

        final URI endpoint;
        try {
            endpoint = EndpointPolicy.validate(settings.endpoint(), settings);
        } catch (IllegalArgumentException exception) {
            synchronized (connectionLock) {
                lastError = exception.getMessage();
            }
            enqueueBroadcast("Automatic pi connection is disabled for this world: " + exception.getMessage(), OutputKind.ERROR);
            LOGGER.warn("Automatic pi connection was not started: {}", exception.getMessage());
            return;
        }
        start(endpoint, settings.sharedToken(), true);
    }

    public void onServerStopping(MinecraftServer stoppingServer) {
        if (server != stoppingServer) {
            return;
        }
        failAllRequests("The world is stopping.");
        stopConnection(true, "Minecraft server stopping");
        outputQueue.clear();
        queuedBroadcasts = 0;
        warnedPlayers.clear();
        minecraftServerName = "minecraft";
        server = null;
    }

    public void onPlayerJoin(ServerPlayerEntity player) {
        if (!isAdministrator(player)) {
            return;
        }
        ConnectionStatus status = status();
        if (status.state() == State.CONNECTED) {
            sendDirect(player, "Pi WebSocket connected to " + status.endpointDescription(), OutputKind.STATUS);
        } else if (status.state() == State.CONNECTING || status.state() == State.RECONNECTING) {
            sendDirect(player, "Pi WebSocket is " + status.state().name().toLowerCase()
                    + " to " + status.endpointDescription()
                    + (status.lastError() == null ? "" : "; last error: " + status.lastError()), OutputKind.STATUS);
        } else if (configStore.get().automaticConnect()) {
            sendDirect(
                    player,
                    "Pi WebSocket is disconnected"
                            + (status.lastError() == null ? "" : ": " + status.lastError())
                            + "; use /websocket status for details.",
                    OutputKind.ERROR
            );
        }
    }

    public void connect(URI endpoint) {
        if (server == null) {
            throw new IllegalStateException("no Minecraft server is running");
        }
        failAllRequests("The pi connection was replaced before this request completed.");
        start(endpoint, configStore.get().sharedToken(), true);
    }

    public boolean disconnect() {
        boolean hadConnection;
        synchronized (connectionLock) {
            hadConnection = desired || connection != null || connectionFuture != null || reconnectFuture != null;
        }
        stopConnection(true, "Disconnected by command");
        failAllRequests("The pi WebSocket was disconnected.");
        return hadConnection;
    }

    public ConnectionStatus status() {
        synchronized (connectionLock) {
            State state;
            if (connection != null && !connection.isOutputClosed()) {
                state = State.CONNECTED;
            } else if (connectionFuture != null) {
                state = reconnectAttempt > 0 ? State.RECONNECTING : State.CONNECTING;
            } else if (reconnectFuture != null) {
                state = State.RECONNECTING;
            } else {
                state = State.DISCONNECTED;
            }
            URI endpoint = activeEndpoint != null ? activeEndpoint : desiredEndpoint;
            String token = activeEndpoint != null ? activeToken : desiredToken;
            return new ConnectionStatus(
                    state,
                    endpoint,
                    EndpointPolicy.display(endpoint, token),
                    reconnectAttempt,
                    lastError
            );
        }
    }

    /** Called by the /pi command on the Minecraft server thread. */
    public int sendPrompt(ServerPlayerEntity player, String prompt) {
        String content = prompt == null ? "" : prompt;
        if (content.isBlank()) {
            sendDirect(player, "Prompt cannot be empty.", OutputKind.ERROR);
            return -1;
        }
        if (content.codePointCount(0, content.length()) > PiProtocol.MAX_PROMPT_CHARACTERS) {
            sendDirect(
                    player,
                    "Prompt is too long (maximum " + PiProtocol.MAX_PROMPT_CHARACTERS + " characters).",
                    OutputKind.ERROR
            );
            return -1;
        }
        if (requests.size() >= MAX_PENDING_REQUESTS) {
            sendDirect(player, "Too many pi prompts are already pending; wait for one to finish.", OutputKind.ERROR);
            return -1;
        }
        long reservedOutputs = (long) (requests.size() + 1) * RESERVED_OUTPUTS_PER_REQUEST;
        if (outputQueue.size() + reservedOutputs + MAX_QUEUED_BROADCASTS > MAX_QUEUED_OUTPUTS) {
            sendDirect(player, "Pi chat output is still draining; wait before sending another prompt.", OutputKind.ERROR);
            return -1;
        }

        WebSocket target;
        long sendGeneration;
        synchronized (connectionLock) {
            target = connection;
            sendGeneration = generation;
            if (target == null || target.isOutputClosed()) {
                sendDirect(player, "Pi WebSocket is not connected. Use /websocket status.", OutputKind.ERROR);
                return -1;
            }
        }

        if (warnedPlayers.add(player.getUuid())) {
            sendDirect(
                    player,
                    "Warning: the connected coding agent may execute commands and modify files.",
                    OutputKind.ERROR
            );
        }

        UUID requestId = UUID.randomUUID();
        RequestState request = new RequestState(player.getUuid(), shortId(requestId), sendGeneration);
        request.timeout = scheduler.schedule(
                () -> postToServer(() -> timeoutRequest(requestId)),
                configStore.get().promptTimeoutSeconds(),
                TimeUnit.SECONDS
        );
        requests.put(requestId, request);

        String payload = PiProtocol.playerMessage(
                requestId,
                player.getUuid(),
                player.getName().getString(),
                content
        );
        enqueueText(target, sendGeneration, payload).whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                postToServer(() -> failRequest(requestId, "The prompt could not be sent because the connection closed."));
                connectionLost(target, sendGeneration, "sending a prompt failed");
            }
        });

        sendDirect(player, "Prompt sent to pi (request " + shortId(requestId) + ").", OutputKind.STATUS);
        return 1;
    }

    /** Drains coalesced streaming output at a bounded rate on the server thread. */
    public void tick(MinecraftServer tickingServer) {
        if (server != tickingServer) {
            return;
        }
        tickCounter++;
        for (RequestState request : requests.values()) {
            for (String text : request.display.flushIfStale(tickCounter, STREAM_FLUSH_TICKS)) {
                queueResponse(request, text);
            }
        }

        if (tickCounter % CHAT_MESSAGE_INTERVAL_TICKS != 0) {
            return;
        }
        for (int sent = 0; sent < CHAT_MESSAGES_PER_TICK && !outputQueue.isEmpty(); sent++) {
            QueuedOutput output = outputQueue.removeFirst();
            if (output.playerId == null) {
                queuedBroadcasts = Math.max(0, queuedBroadcasts - 1);
                sendBroadcastNow(output.text, output.kind);
            } else {
                ServerPlayerEntity player = tickingServer.getPlayerManager().getPlayer(output.playerId);
                if (player != null) {
                    sendDirect(player, output.text, output.kind);
                }
            }
        }
    }

    private void start(URI endpoint, String sharedToken, boolean announce) {
        WebSocket previous;
        CompletableFuture<WebSocket> previousFuture;
        ScheduledFuture<?> previousReconnect;
        long attemptGeneration;
        CompletableFuture<WebSocket> attempt;

        synchronized (connectionLock) {
            desired = true;
            desiredEndpoint = endpoint;
            desiredToken = sharedToken;
            if (announce) {
                reconnectAttempt = 0;
                lastError = null;
            }
            previous = connection;
            previousFuture = connectionFuture;
            previousReconnect = reconnectFuture;
            connection = null;
            activeEndpoint = null;
            activeToken = "";
            connectionFuture = null;
            reconnectFuture = null;
            sendChain = CompletableFuture.completedFuture(null);
            attemptGeneration = ++generation;

            try {
                java.net.http.WebSocket.Builder builder = httpClient.newWebSocketBuilder()
                        .connectTimeout(CONNECT_TIMEOUT);
                if (!sharedToken.isBlank()) {
                    builder.header("Authorization", "Bearer " + sharedToken);
                }
                attempt = builder.buildAsync(endpoint, new SocketListener(attemptGeneration));
                attempt.orTimeout(CONNECT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                connectionFuture = attempt;
            } catch (RuntimeException exception) {
                connectionFuture = null;
                closeOld(previous, previousFuture, previousReconnect, "Replaced by failed connection attempt");
                connectionAttemptFailed(attemptGeneration, endpoint, sharedToken, exception);
                return;
            }
        }

        closeOld(previous, previousFuture, previousReconnect, "Replaced by a new connection");
        if (announce) {
            enqueueBroadcast(
                    "Connecting to pi at " + displayEndpoint(endpoint, sharedToken) + "...",
                    OutputKind.STATUS
            );
        }

        attempt.whenComplete((webSocket, throwable) -> {
            boolean installed = false;
            CompletableFuture<Void> helloSend = null;
            synchronized (connectionLock) {
                if (attemptGeneration != generation || connectionFuture != attempt || !desired) {
                    if (throwable == null) {
                        webSocket.abort();
                    }
                    return;
                }
                connectionFuture = null;
                if (throwable == null && !webSocket.isInputClosed() && !webSocket.isOutputClosed()) {
                    try {
                        connection = webSocket;
                        activeEndpoint = endpoint;
                        activeToken = sharedToken;
                        reconnectAttempt = 0;
                        lastError = null;
                        helloSend = webSocket.sendText(PiProtocol.hello(minecraftServerName), true)
                                .thenApply(socket -> null);
                        sendChain = helloSend;
                        installed = true;
                    } catch (RuntimeException exception) {
                        connection = null;
                        activeEndpoint = null;
                        activeToken = "";
                        sendChain = CompletableFuture.completedFuture(null);
                        webSocket.abort();
                    }
                }
            }

            if (!installed) {
                if (throwable == null) {
                    connectionAttemptFailed(
                            attemptGeneration,
                            endpoint,
                            sharedToken,
                            new IllegalStateException("connection closed during setup")
                    );
                } else {
                    connectionAttemptFailed(attemptGeneration, endpoint, sharedToken, throwable);
                }
                return;
            }

            LOGGER.info("Connected pi WebSocket to {}", displayEndpoint(endpoint, sharedToken));
            enqueueBroadcast(
                    "Connected to pi at " + displayEndpoint(endpoint, sharedToken) + ".",
                    OutputKind.STATUS
            );
            helloSend.whenComplete((ignored, error) -> {
                if (error != null) {
                    connectionLost(webSocket, attemptGeneration, "sending the hello message failed");
                }
            });
        });
    }

    private CompletableFuture<Void> enqueueText(WebSocket target, long sendGeneration, String payload) {
        synchronized (connectionLock) {
            if (target != connection || sendGeneration != generation || target.isOutputClosed()) {
                return CompletableFuture.failedFuture(new IllegalStateException("WebSocket is not connected"));
            }
            CompletableFuture<Void> send = sendChain
                    .handle((ignored, throwable) -> null)
                    .thenCompose(ignored -> target.sendText(payload, true).thenApply(socket -> null));
            sendChain = send;
            return send;
        }
    }

    private void connectionAttemptFailed(
            long failedGeneration,
            URI endpoint,
            String sharedToken,
            Throwable throwable
    ) {
        String reason = safeError(throwable, endpoint, sharedToken);
        synchronized (connectionLock) {
            if (failedGeneration != generation || !desired) {
                return;
            }
            connection = null;
            activeEndpoint = null;
            activeToken = "";
            connectionFuture = null;
            sendChain = CompletableFuture.completedFuture(null);
            lastError = reason;
        }
        LOGGER.warn(
                "Pi WebSocket connection to {} failed: {}",
                displayEndpoint(endpoint, sharedToken),
                reason
        );
        enqueueBroadcast(
                "Pi WebSocket connection failed: " + reason + ".",
                OutputKind.ERROR
        );
        scheduleReconnect(failedGeneration);
    }

    private void connectionLost(WebSocket socket, long socketGeneration, String reason) {
        synchronized (connectionLock) {
            if (socketGeneration != generation || connection != socket || !desired) {
                return;
            }
            connection = null;
            activeEndpoint = null;
            activeToken = "";
            sendChain = CompletableFuture.completedFuture(null);
            lastError = reason;
        }
        LOGGER.warn("Pi WebSocket disconnected: {}", reason);
        postToServer(() -> failRequestsForGeneration(
                socketGeneration,
                "The pi connection was lost before the response completed."
        ));
        scheduleReconnect(socketGeneration);
    }

    private void scheduleReconnect(long failedGeneration) {
        int attempt;
        int delay;
        URI endpoint;
        String sharedToken;
        synchronized (connectionLock) {
            if (failedGeneration != generation || !desired || server == null || reconnectFuture != null) {
                return;
            }
            attempt = ++reconnectAttempt;
            delay = ReconnectBackoff.delaySeconds(attempt);
            endpoint = desiredEndpoint;
            sharedToken = desiredToken;
            MinecraftServer expectedServer = server;
            reconnectFuture = scheduler.schedule(() -> {
                synchronized (connectionLock) {
                    if (failedGeneration != generation
                            || !desired
                            || server != expectedServer
                            || reconnectFuture == null) {
                        return;
                    }
                    reconnectFuture = null;
                    // Keep the lifecycle check and reconnect start atomic with respect to server stop.
                    start(endpoint, sharedToken, false);
                }
            }, delay, TimeUnit.SECONDS);
        }
        LOGGER.info(
                "Scheduling pi WebSocket reconnect attempt {} in {} seconds to {}",
                attempt,
                delay,
                displayEndpoint(endpoint, sharedToken)
        );
        enqueueBroadcast(
                "Pi WebSocket reconnect attempt " + attempt + " in " + delay + " second"
                        + (delay == 1 ? "" : "s") + ".",
                OutputKind.STATUS
        );
    }

    private void stopConnection(boolean graceful, String reason) {
        WebSocket oldConnection;
        CompletableFuture<WebSocket> oldFuture;
        ScheduledFuture<?> oldReconnect;
        synchronized (connectionLock) {
            desired = false;
            ++generation;
            oldConnection = connection;
            oldFuture = connectionFuture;
            oldReconnect = reconnectFuture;
            connection = null;
            activeEndpoint = null;
            activeToken = "";
            desiredEndpoint = null;
            desiredToken = "";
            connectionFuture = null;
            reconnectFuture = null;
            reconnectAttempt = 0;
            lastError = null;
            sendChain = CompletableFuture.completedFuture(null);
        }
        closeOld(oldConnection, oldFuture, oldReconnect, reason, graceful);
    }

    private void closeOld(
            WebSocket oldConnection,
            CompletableFuture<WebSocket> oldFuture,
            ScheduledFuture<?> oldReconnect,
            String reason
    ) {
        closeOld(oldConnection, oldFuture, oldReconnect, reason, true);
    }

    private void closeOld(
            WebSocket oldConnection,
            CompletableFuture<WebSocket> oldFuture,
            ScheduledFuture<?> oldReconnect,
            String reason,
            boolean graceful
    ) {
        if (oldReconnect != null) {
            oldReconnect.cancel(false);
        }
        if (oldFuture != null) {
            oldFuture.cancel(true);
        }
        if (oldConnection == null) {
            return;
        }
        if (!graceful) {
            oldConnection.abort();
            return;
        }

        oldConnection.sendClose(WebSocket.NORMAL_CLOSURE, truncate(reason, 120))
                .whenComplete((ignored, throwable) -> {
                    if (throwable != null) {
                        oldConnection.abort();
                    }
                });
        scheduler.schedule(() -> {
            if (!oldConnection.isOutputClosed()) {
                oldConnection.abort();
            }
        }, 2, TimeUnit.SECONDS);
    }

    private void handleIncoming(String payload, long messageGeneration) {
        synchronized (connectionLock) {
            if (messageGeneration != generation || connection == null) {
                return;
            }
        }

        final PiProtocol.ServerMessage message;
        try {
            message = PiProtocol.decodeServerMessage(payload);
        } catch (PiProtocol.ProtocolException exception) {
            LOGGER.warn("Ignored malformed pi protocol message: {}", exception.getMessage());
            enqueueBroadcast("Pi protocol error: " + exception.getMessage() + ".", OutputKind.ERROR);
            return;
        }

        switch (message) {
            case PiProtocol.Chunk chunk -> handleChunk(chunk);
            case PiProtocol.Complete complete -> handleComplete(complete);
            case PiProtocol.ErrorMessage error -> handleError(error);
            case PiProtocol.Status status -> handleRemoteStatus(status);
        }
    }

    private void handleChunk(PiProtocol.Chunk chunk) {
        RequestState request = requests.get(chunk.requestId());
        if (request == null) {
            LOGGER.warn("Ignored a pi response chunk for an unknown request");
            enqueueBroadcast("Pi sent a response for an unknown or expired request.", OutputKind.ERROR);
            return;
        }
        if (chunk.sequence() != request.nextSequence) {
            failRequest(
                    chunk.requestId(),
                    "Pi sent response chunks out of order (expected " + request.nextSequence
                            + ", received " + chunk.sequence() + ")."
            );
            return;
        }
        if ((long) request.responseCharacters + chunk.content().length() > MAX_RESPONSE_CHARACTERS) {
            failRequest(chunk.requestId(), "Pi response exceeded the safe display limit.");
            return;
        }

        request.nextSequence++;
        request.responseCharacters += chunk.content().length();
        if (!chunk.content().isEmpty()) {
            request.receivedContent = true;
            String safeContent = sanitizeResponse(chunk.content());
            for (String text : request.display.append(safeContent, tickCounter)) {
                queueResponse(request, text);
            }
        }
    }

    private void handleComplete(PiProtocol.Complete complete) {
        RequestState request = requests.get(complete.requestId());
        if (request == null) {
            LOGGER.warn("Ignored completion for an unknown pi request");
            return;
        }
        if (complete.sequence() != request.nextSequence) {
            failRequest(
                    complete.requestId(),
                    "Pi completed with a missing or out-of-order response chunk."
            );
            return;
        }
        finishRequest(complete.requestId(), request);
        if (!request.receivedContent) {
            queueForPlayer(request.playerId, "Pi completed without response text.", OutputKind.STATUS);
        }
    }

    private void handleError(PiProtocol.ErrorMessage error) {
        String safeMessage = sanitizeSingleLine(error.message(), 512);
        if (error.requestId() == null) {
            enqueueBroadcast("Pi error (" + sanitizeSingleLine(error.code(), 64) + "): " + safeMessage, OutputKind.ERROR);
            return;
        }
        failRequest(error.requestId(), "Pi error (" + sanitizeSingleLine(error.code(), 64) + "): " + safeMessage);
    }

    private void handleRemoteStatus(PiProtocol.Status status) {
        StringBuilder text = new StringBuilder("Pi session: ")
                .append(sanitizeSingleLine(status.status(), 64));
        if (status.message() != null && !status.message().isBlank()) {
            text.append(" — ").append(sanitizeSingleLine(status.message(), 512));
        }
        enqueueBroadcast(text.toString(), OutputKind.STATUS);
    }

    private void timeoutRequest(UUID requestId) {
        failRequest(requestId, "Pi did not complete the request before the configured timeout.");
    }

    private void failRequest(UUID requestId, String reason) {
        RequestState request = requests.remove(requestId);
        if (request == null) {
            return;
        }
        request.cancelTimeout();
        for (String text : request.display.flush()) {
            queueResponse(request, text);
        }
        queueForPlayer(request.playerId, "[" + request.label + "] " + reason, OutputKind.ERROR);
    }

    private void finishRequest(UUID requestId, RequestState request) {
        requests.remove(requestId);
        request.cancelTimeout();
        for (String text : request.display.flush()) {
            queueResponse(request, text);
        }
    }

    private void failAllRequests(String reason) {
        for (Map.Entry<UUID, RequestState> entry : java.util.List.copyOf(requests.entrySet())) {
            failRequest(entry.getKey(), reason);
        }
    }

    private void failRequestsForGeneration(long failedGeneration, String reason) {
        for (Map.Entry<UUID, RequestState> entry : java.util.List.copyOf(requests.entrySet())) {
            if (entry.getValue().connectionGeneration == failedGeneration) {
                failRequest(entry.getKey(), reason);
            }
        }
    }

    private void queueResponse(RequestState request, String text) {
        if (request.queuedComponents >= MAX_CHAT_COMPONENTS_PER_REQUEST) {
            if (!request.displayLimitReported) {
                request.displayLimitReported = true;
                queueForPlayer(
                        request.playerId,
                        "[" + request.label + "] Response display limit reached; later text was omitted.",
                        OutputKind.ERROR
                );
            }
            return;
        }
        request.queuedComponents++;
        queueForPlayer(request.playerId, "[" + request.label + "] " + text, OutputKind.RESPONSE);
    }

    private void queueForPlayer(UUID playerId, String text, OutputKind kind) {
        if (outputQueue.size() < MAX_QUEUED_OUTPUTS) {
            outputQueue.addLast(new QueuedOutput(playerId, text, kind));
        }
    }

    private void enqueueBroadcast(String text, OutputKind kind) {
        postToServer(() -> {
            MinecraftServer activeServer = server;
            if (activeServer == null
                    || outputQueue.size() >= MAX_QUEUED_OUTPUTS
                    || queuedBroadcasts >= MAX_QUEUED_BROADCASTS) {
                return;
            }
            boolean operatorOnline = activeServer.getPlayerManager().getPlayerList().stream()
                    .anyMatch(this::isAdministrator);
            if (operatorOnline) {
                outputQueue.addLast(new QueuedOutput(null, text, kind));
                queuedBroadcasts++;
            }
        });
    }

    private void sendBroadcastNow(String text, OutputKind kind) {
        MinecraftServer activeServer = server;
        if (activeServer != null) {
            for (ServerPlayerEntity player : activeServer.getPlayerManager().getPlayerList()) {
                if (isAdministrator(player)) {
                    sendDirect(player, text, kind);
                }
            }
        }
    }

    private boolean isAdministrator(ServerPlayerEntity player) {
        MinecraftServer activeServer = server;
        return player.getPermissionLevel() >= 2
                || (activeServer != null
                && !activeServer.isDedicated()
                && activeServer.isHost(player.getPlayerConfigEntry()));
    }

    private static void sendDirect(ServerPlayerEntity player, String text, OutputKind kind) {
        player.sendMessage(component(text, kind), false);
    }

    private static Text component(String text, OutputKind kind) {
        Text prefix = switch (kind) {
            case RESPONSE -> Text.literal("[pi] ").formatted(Formatting.AQUA);
            case STATUS -> Text.literal("[pi] ").formatted(Formatting.GRAY);
            case ERROR -> Text.literal("[pi] ").formatted(Formatting.RED);
        };
        return prefix.copy().append(Text.literal(text == null || text.isEmpty() ? " " : text));
    }

    private void postToServer(Runnable action) {
        MinecraftServer target = server;
        if (target == null) {
            return;
        }
        target.execute(() -> {
            if (server == target) {
                action.run();
            }
        });
    }

    private static String displayEndpoint(URI endpoint, String sharedToken) {
        return EndpointPolicy.display(endpoint, sharedToken);
    }

    private String safeError(Throwable throwable, URI endpoint, String sharedToken) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            message = cause.getClass().getSimpleName();
        }

        if (sharedToken != null && !sharedToken.isBlank()) {
            message = message.replace(sharedToken, "<redacted>");
        }
        if (endpoint != null) {
            message = message.replace(endpoint.toString(), displayEndpoint(endpoint, sharedToken));
            if (endpoint.getRawQuery() != null) {
                message = message.replace(endpoint.getRawQuery(), "<redacted>");
            }
        }
        return sanitizeSingleLine(message, 240);
    }

    private static String sanitizeResponse(String value) {
        StringBuilder result = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (codePoint == '\n' || codePoint == '\t' || codePoint >= 0x20) {
                result.appendCodePoint(codePoint);
            }
        });
        return result.toString();
    }

    private static String sanitizeSingleLine(String value, int maximumCodePoints) {
        if (value == null) {
            return "";
        }
        StringBuilder result = new StringBuilder(Math.min(value.length(), maximumCodePoints));
        int offset = 0;
        int accepted = 0;
        while (offset < value.length() && accepted < maximumCodePoints) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '\n' || codePoint == '\r' || codePoint == '\t') {
                result.append(' ');
                accepted++;
            } else if (codePoint >= 0x20) {
                result.appendCodePoint(codePoint);
                accepted++;
            }
        }
        return result.toString();
    }

    private static String truncate(String value, int maximumLength) {
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }

    private static String truncateUtf16(String value, int maximumCodeUnits) {
        if (value.length() <= maximumCodeUnits) {
            return value;
        }
        int end = maximumCodeUnits;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    public enum State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING
    }

    public record ConnectionStatus(
            State state,
            URI endpoint,
            String endpointDescription,
            int reconnectAttempt,
            String lastError
    ) {}

    private enum OutputKind {
        RESPONSE,
        STATUS,
        ERROR
    }

    private static final class RequestState {
        private final UUID playerId;
        private final String label;
        private final long connectionGeneration;
        private final StreamingTextBuffer display = new StreamingTextBuffer(CHAT_COMPONENT_CODE_POINTS);
        private int nextSequence;
        private int responseCharacters;
        private boolean receivedContent;
        private int queuedComponents;
        private boolean displayLimitReported;
        private ScheduledFuture<?> timeout;

        private RequestState(UUID playerId, String label, long connectionGeneration) {
            this.playerId = playerId;
            this.label = label;
            this.connectionGeneration = connectionGeneration;
        }

        private void cancelTimeout() {
            if (timeout != null) {
                timeout.cancel(false);
            }
        }
    }

    private record QueuedOutput(UUID playerId, String text, OutputKind kind) {}

    private final class SocketListener implements WebSocket.Listener {
        private final long socketGeneration;
        private final StringBuilder text = new StringBuilder();
        private int textBytes;
        private boolean discardingOversizedMessage;

        private SocketListener(long socketGeneration) {
            this.socketGeneration = socketGeneration;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (!discardingOversizedMessage) {
                int fragmentBytes = data.toString().getBytes(StandardCharsets.UTF_8).length;
                if ((long) text.length() + data.length() > PiProtocol.MAX_FRAME_BYTES
                        || (long) textBytes + fragmentBytes > PiProtocol.MAX_FRAME_BYTES) {
                    text.setLength(0);
                    textBytes = 0;
                    discardingOversizedMessage = true;
                } else {
                    text.append(data);
                    textBytes += fragmentBytes;
                }
            }

            if (!last) {
                webSocket.request(1);
                return null;
            }
            if (discardingOversizedMessage) {
                discardingOversizedMessage = false;
                enqueueBroadcast("Pi protocol error: incoming message exceeded the frame limit.", OutputKind.ERROR);
                return webSocket.sendClose(1009, "message too large");
            }

            String payload = text.toString();
            text.setLength(0);
            textBytes = 0;
            CompletableFuture<Void> processed = new CompletableFuture<>();
            MinecraftServer target = server;
            if (target == null) {
                processed.complete(null);
                return processed;
            }
            try {
                target.execute(() -> {
                    try {
                        if (server == target) {
                            handleIncoming(payload, socketGeneration);
                        }
                        if (!webSocket.isInputClosed() && !webSocket.isOutputClosed()) {
                            webSocket.request(1);
                        }
                        processed.complete(null);
                    } catch (RuntimeException exception) {
                        processed.completeExceptionally(exception);
                        webSocket.abort();
                    }
                });
            } catch (RuntimeException exception) {
                processed.completeExceptionally(exception);
                webSocket.abort();
            }
            return processed;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            enqueueBroadcast("Pi protocol error: binary messages are not supported.", OutputKind.ERROR);
            return webSocket.sendClose(1003, "text messages required");
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            webSocket.request(1);
            return webSocket.sendPong(message);
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            LOGGER.info("Pi WebSocket closed with status {}", statusCode);
            connectionLost(webSocket, socketGeneration, "remote close (status " + statusCode + ")");
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            connectionLost(webSocket, socketGeneration, "transport error");
        }
    }
}
