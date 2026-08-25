package ca.cmoyates.http_request_mod.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class WebSocketCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger("http_request_mod/websocket");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Set<String> WEB_SOCKET_SCHEMES = Set.of("ws", "wss");
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private static final Object CONNECTION_LOCK = new Object();

    private static WebSocket connection;
    private static URI connectedEndpoint;
    private static URI connectingEndpoint;
    private static CompletableFuture<WebSocket> connectionFuture;
    private static CompletableFuture<Void> sendChain = CompletableFuture.completedFuture(null);
    private static long connectionGeneration;

    private WebSocketCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                registerCommands(dispatcher));

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) ->
                sendChatMessage(message.getContent().getString()));

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> disconnect(false));
    }

    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                literal("websocket")
                        // Connecting to an arbitrary network service is restricted to operators.
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(literal("connect")
                                .then(argument("URL", StringArgumentType.greedyString())
                                        .executes(ctx -> connect(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "URL")
                                        ))
                                )
                        )
                        .then(literal("disconnect")
                                .executes(ctx -> disconnect(ctx.getSource()))
                        )
                        .then(literal("status")
                                .executes(ctx -> status(ctx.getSource()))
                        )
        );
    }

    private static int connect(ServerCommandSource source, String url) {
        URI endpoint;
        try {
            endpoint = validateEndpoint(url);
        } catch (IllegalArgumentException exception) {
            source.sendError(Text.literal("Invalid WebSocket URL: " + url + " (expected ws:// or wss://)"));
            return -1;
        }

        source.sendFeedback(() -> Text.literal("Connecting to " + endpoint + "..."), false);

        WebSocket previousConnection = null;
        CompletableFuture<WebSocket> previousFuture = null;
        CompletableFuture<WebSocket> newFuture;
        long generation = -1;

        try {
            synchronized (CONNECTION_LOCK) {
                generation = ++connectionGeneration;
                previousConnection = connection;
                previousFuture = connectionFuture;

                connection = null;
                connectedEndpoint = null;
                connectingEndpoint = endpoint;
                sendChain = CompletableFuture.completedFuture(null);

                newFuture = HTTP_CLIENT.newWebSocketBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .buildAsync(endpoint, new SocketListener(generation));
                newFuture.orTimeout(CONNECT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                connectionFuture = newFuture;
            }
        } catch (RuntimeException exception) {
            synchronized (CONNECTION_LOCK) {
                if (generation == connectionGeneration) {
                    connectingEndpoint = null;
                    connectionFuture = null;
                }
            }
            closePreviousConnection(previousConnection, previousFuture);
            source.sendError(Text.literal("Could not start WebSocket connection: " + errorMessage(exception)));
            return -1;
        }

        closePreviousConnection(previousConnection, previousFuture);

        MinecraftServer server = source.getServer();
        long completedGeneration = generation;
        newFuture.whenComplete((webSocket, throwable) -> {
            boolean currentAttempt;
            synchronized (CONNECTION_LOCK) {
                currentAttempt = completedGeneration == connectionGeneration && connectionFuture == newFuture;

                if (currentAttempt) {
                    connectionFuture = null;
                    connectingEndpoint = null;

                    if (throwable == null && !webSocket.isInputClosed() && !webSocket.isOutputClosed()) {
                        connection = webSocket;
                        connectedEndpoint = endpoint;
                    }
                }
            }

            if (!currentAttempt) {
                if (throwable == null) {
                    webSocket.abort();
                }
                return;
            }

            server.execute(() -> {
                if (throwable == null && !webSocket.isInputClosed() && !webSocket.isOutputClosed()) {
                    source.sendFeedback(() -> Text.literal("Connected to " + endpoint), false);
                } else if (throwable == null) {
                    source.sendError(Text.literal("WebSocket connection to " + endpoint + " closed during setup"));
                } else {
                    source.sendError(Text.literal(
                            "WebSocket connection to " + endpoint + " failed: " + errorMessage(throwable)
                    ));
                }
            });
        });

        return 1;
    }

    private static int disconnect(ServerCommandSource source) {
        URI endpoint;
        boolean hadConnection;

        synchronized (CONNECTION_LOCK) {
            endpoint = connectedEndpoint != null ? connectedEndpoint : connectingEndpoint;
            hadConnection = connection != null || connectionFuture != null;
        }

        if (!hadConnection) {
            source.sendFeedback(() -> Text.literal("No WebSocket is connected"), false);
            return 0;
        }

        disconnect(true);
        source.sendFeedback(
                () -> Text.literal(endpoint == null
                        ? "Disconnected from WebSocket"
                        : "Disconnected from " + endpoint),
                false
        );
        return 1;
    }

    private static int status(ServerCommandSource source) {
        URI activeEndpoint;
        URI pendingEndpoint;

        synchronized (CONNECTION_LOCK) {
            activeEndpoint = connection == null ? null : connectedEndpoint;
            pendingEndpoint = connectionFuture == null ? null : connectingEndpoint;
        }

        if (activeEndpoint != null) {
            source.sendFeedback(() -> Text.literal("WebSocket connected to " + activeEndpoint), false);
        } else if (pendingEndpoint != null) {
            source.sendFeedback(() -> Text.literal("WebSocket connecting to " + pendingEndpoint), false);
        } else {
            source.sendFeedback(() -> Text.literal("WebSocket disconnected"), false);
        }

        return activeEndpoint != null ? 1 : 0;
    }

    private static void sendChatMessage(String message) {
        WebSocket target;
        CompletableFuture<Void> send;
        long generation;

        synchronized (CONNECTION_LOCK) {
            target = connection;
            generation = connectionGeneration;

            if (target == null || target.isOutputClosed()) {
                return;
            }

            // WebSocket only permits one outstanding text send. Chaining also preserves chat order.
            send = sendChain
                    .handle((ignored, throwable) -> null)
                    .thenCompose(ignored -> target.sendText(message, true).thenApply(webSocket -> null));
            sendChain = send;
        }

        send.whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                LOGGER.warn("Failed to send a chat message through the WebSocket", throwable);
                handleClosedConnection(target, generation);
            }
        });
    }

    private static void closePreviousConnection(
            WebSocket previousConnection,
            CompletableFuture<WebSocket> previousFuture
    ) {
        if (previousFuture != null) {
            previousFuture.cancel(true);
        }
        if (previousConnection != null) {
            previousConnection.sendClose(WebSocket.NORMAL_CLOSURE, "Replaced by a new connection")
                    .exceptionally(throwable -> {
                        previousConnection.abort();
                        return previousConnection;
                    });
        }
    }

    private static void disconnect(boolean graceful) {
        WebSocket oldConnection;
        CompletableFuture<WebSocket> oldFuture;

        synchronized (CONNECTION_LOCK) {
            ++connectionGeneration;
            oldConnection = connection;
            oldFuture = connectionFuture;
            connection = null;
            connectedEndpoint = null;
            connectingEndpoint = null;
            connectionFuture = null;
            sendChain = CompletableFuture.completedFuture(null);
        }

        if (oldFuture != null) {
            oldFuture.cancel(true);
        }
        if (oldConnection != null) {
            if (graceful) {
                oldConnection.sendClose(WebSocket.NORMAL_CLOSURE, "Disconnected by server")
                        .exceptionally(throwable -> {
                            oldConnection.abort();
                            return oldConnection;
                        });
            } else {
                oldConnection.abort();
            }
        }
    }

    private static void handleClosedConnection(WebSocket webSocket, long generation) {
        synchronized (CONNECTION_LOCK) {
            if (generation != connectionGeneration) {
                return;
            }

            // During setup, the connection future owns the state and reports the failure to
            // the command source. Only clear a socket that was fully installed as active here.
            if (connection == webSocket) {
                ++connectionGeneration;
                connection = null;
                connectedEndpoint = null;
                connectingEndpoint = null;
                connectionFuture = null;
                sendChain = CompletableFuture.completedFuture(null);
            }
        }
    }

    private static URI validateEndpoint(String url) {
        URI endpoint = URI.create(url);
        String scheme = endpoint.getScheme();

        if (scheme == null
                || !WEB_SOCKET_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))
                || endpoint.getHost() == null) {
            throw new IllegalArgumentException("URL must be an absolute WebSocket URL");
        }

        return endpoint;
    }

    private static String errorMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }

        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private static final class SocketListener implements WebSocket.Listener {
        private final long generation;

        private SocketListener(long generation) {
            this.generation = generation;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            // Incoming data is intentionally ignored; keep requesting frames so the connection stays usable.
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            LOGGER.info("WebSocket closed (status {}): {}", statusCode, reason);
            handleClosedConnection(webSocket, generation);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            LOGGER.warn("WebSocket connection failed", error);
            handleClosedConnection(webSocket, generation);
        }
    }
}
