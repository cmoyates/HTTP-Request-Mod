package ca.cmoyates.http_request_mod.commands;

import ca.cmoyates.http_request_mod.config.WebSocketConfigStore;
import ca.cmoyates.http_request_mod.config.WebSocketSettings;
import ca.cmoyates.http_request_mod.websocket.EndpointPolicy;
import ca.cmoyates.http_request_mod.websocket.PiWebSocketClient;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/** Registers the administrative WebSocket commands and the explicit /pi prompt command. */
public final class WebSocketCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger("http_request_mod/websocket");
    private static final WebSocketConfigStore CONFIG = new WebSocketConfigStore(
            FabricLoader.getInstance().getConfigDir().resolve("http-request-mod.json")
    );
    private static final PiWebSocketClient CLIENT = new PiWebSocketClient(CONFIG);
    private static boolean registered;

    private WebSocketCommand() {}

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;

        try {
            WebSocketSettings settings = CONFIG.reload();
            if (!settings.endpoint().isBlank()) {
                EndpointPolicy.validate(settings.endpoint(), settings);
            }
        } catch (IOException | IllegalArgumentException exception) {
            // Deliberately omit exception details because malformed configuration can contain a token.
            CONFIG.restore(WebSocketSettings.defaults());
            LOGGER.warn("Could not load a valid policy-compliant configuration from {}; safe defaults will be used", CONFIG.path());
        }

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                registerCommands(dispatcher));
        ServerLifecycleEvents.SERVER_STARTED.register(CLIENT::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(CLIENT::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(CLIENT::tick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                CLIENT.onPlayerJoin(handler.player));
    }

    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                literal("websocket")
                        .requires(WebSocketCommand::canAdminister)
                        .then(literal("connect")
                                .executes(ctx -> connectConfigured(ctx.getSource()))
                                .then(argument("URL", StringArgumentType.greedyString())
                                        .executes(ctx -> selectAndConnect(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "URL")
                                        ))
                                )
                        )
                        .then(literal("disconnect")
                                .executes(ctx -> disconnect(ctx.getSource()))
                        )
                        .then(literal("endpoint")
                                .executes(ctx -> showEndpoint(ctx.getSource()))
                                .then(literal("clear")
                                        .executes(ctx -> clearEndpoint(ctx.getSource()))
                                )
                                .then(argument("URL", StringArgumentType.greedyString())
                                        .executes(ctx -> selectEndpoint(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "URL")
                                        ))
                                )
                        )
                        .then(literal("autoconnect")
                                .then(argument("enabled", BoolArgumentType.bool())
                                        .executes(ctx -> setAutomaticConnect(
                                                ctx.getSource(),
                                                BoolArgumentType.getBool(ctx, "enabled")
                                        ))
                                )
                        )
                        .then(literal("status")
                                .executes(ctx -> status(ctx.getSource()))
                        )
                        .then(literal("reload")
                                .executes(ctx -> reload(ctx.getSource()))
                        )
        );

        dispatcher.register(
                literal("pi")
                        // The companion coding agent may execute commands and modify files.
                        .requires(source -> source.isExecutedByPlayer() && canAdminister(source))
                        .then(argument("prompt", StringArgumentType.greedyString())
                                .executes(ctx -> sendPrompt(
                                        ctx.getSource().getPlayerOrThrow(),
                                        StringArgumentType.getString(ctx, "prompt")
                                ))
                        )
        );
    }

    private static int connectConfigured(ServerCommandSource source) {
        WebSocketSettings settings = CONFIG.get();
        final URI endpoint;
        try {
            endpoint = EndpointPolicy.validate(settings.endpoint(), settings);
        } catch (IllegalArgumentException exception) {
            source.sendError(Text.literal("Cannot connect: " + exception.getMessage()));
            return -1;
        }

        CLIENT.connect(endpoint);
        source.sendFeedback(
                () -> Text.literal("Starting pi WebSocket connection to " + displayEndpoint(endpoint)),
                false
        );
        return 1;
    }

    private static int selectAndConnect(ServerCommandSource source, String value) {
        int selected = selectEndpoint(source, value);
        return selected < 0 ? selected : connectConfigured(source);
    }

    private static int selectEndpoint(ServerCommandSource source, String value) {
        WebSocketSettings current = CONFIG.get();
        final URI endpoint;
        try {
            endpoint = EndpointPolicy.validate(value, current);
        } catch (IllegalArgumentException exception) {
            source.sendError(Text.literal("Invalid WebSocket endpoint: " + exception.getMessage()));
            return -1;
        }

        try {
            CONFIG.save(current.withEndpoint(endpoint.toString()));
        } catch (IOException exception) {
            LOGGER.warn("Could not persist the pi WebSocket endpoint");
            source.sendError(Text.literal("Could not save the WebSocket configuration; see the server log."));
            return -1;
        }

        source.sendFeedback(
                () -> Text.literal("Saved pi WebSocket endpoint " + displayEndpoint(endpoint)),
                false
        );
        return 1;
    }

    private static int clearEndpoint(ServerCommandSource source) {
        try {
            CONFIG.save(CONFIG.get().withEndpoint(""));
        } catch (IOException exception) {
            LOGGER.warn("Could not clear the pi WebSocket endpoint");
            source.sendError(Text.literal("Could not save the WebSocket configuration; see the server log."));
            return -1;
        }
        source.sendFeedback(() -> Text.literal("Cleared the configured pi WebSocket endpoint"), false);
        return 1;
    }

    private static int showEndpoint(ServerCommandSource source) {
        String configured = CONFIG.get().endpoint();
        if (configured.isBlank()) {
            source.sendFeedback(() -> Text.literal("No pi WebSocket endpoint is configured"), false);
            return 0;
        }
        try {
            URI endpoint = URI.create(configured);
            source.sendFeedback(
                    () -> Text.literal("Configured pi WebSocket endpoint: " + displayEndpoint(endpoint)),
                    false
            );
        } catch (IllegalArgumentException exception) {
            source.sendError(Text.literal("The configured pi WebSocket endpoint is invalid"));
            return -1;
        }
        return 1;
    }

    private static int setAutomaticConnect(ServerCommandSource source, boolean enabled) {
        try {
            CONFIG.save(CONFIG.get().withAutomaticConnect(enabled));
        } catch (IOException exception) {
            LOGGER.warn("Could not persist the pi WebSocket automatic-connect setting");
            source.sendError(Text.literal("Could not save the WebSocket configuration; see the server log."));
            return -1;
        }
        source.sendFeedback(
                () -> Text.literal("Automatic pi WebSocket connection " + (enabled ? "enabled" : "disabled")),
                false
        );
        return 1;
    }

    private static int disconnect(ServerCommandSource source) {
        boolean disconnected = CLIENT.disconnect();
        source.sendFeedback(
                () -> Text.literal(disconnected
                        ? "Disconnected the pi WebSocket; reconnection is paused until a manual connect or world restart"
                        : "Pi WebSocket is already disconnected"),
                false
        );
        return disconnected ? 1 : 0;
    }

    private static int status(ServerCommandSource source) {
        PiWebSocketClient.ConnectionStatus connection = CLIENT.status();
        WebSocketSettings settings = CONFIG.get();
        String endpoint = connection.endpointDescription();
        source.sendFeedback(
                () -> Text.literal(
                        "Pi WebSocket: " + connection.state().name().toLowerCase()
                                + "; endpoint=" + endpoint
                                + "; autoconnect=" + settings.automaticConnect()
                                + "; authentication=" + (settings.hasSharedToken() ? "configured" : "not configured")
                                + (connection.reconnectAttempt() > 0
                                ? "; reconnect attempt=" + connection.reconnectAttempt()
                                : "")
                                + (connection.lastError() == null
                                ? ""
                                : "; last error=" + connection.lastError())
                ),
                false
        );
        return connection.state() == PiWebSocketClient.State.CONNECTED ? 1 : 0;
    }

    private static int reload(ServerCommandSource source) {
        WebSocketSettings previousSettings = CONFIG.get();
        try {
            WebSocketSettings settings = CONFIG.reload();
            // Validate without displaying the value; reload itself does not change the active connection.
            if (!settings.endpoint().isBlank()) {
                EndpointPolicy.validate(settings.endpoint(), settings);
            }
            source.sendFeedback(
                    () -> Text.literal("Reloaded pi WebSocket configuration; reconnect to apply connection changes"),
                    false
            );
            return 1;
        } catch (IOException exception) {
            CONFIG.restore(previousSettings);
            LOGGER.warn("Could not reload the pi WebSocket configuration");
            source.sendError(Text.literal("Could not reload the WebSocket configuration; existing settings remain active."));
            return -1;
        } catch (IllegalArgumentException exception) {
            CONFIG.restore(previousSettings);
            LOGGER.warn("Reloaded pi WebSocket configuration contains an invalid endpoint");
            source.sendError(Text.literal(
                    "Configuration endpoint is invalid; existing settings remain active: " + exception.getMessage()
            ));
            return -1;
        }
    }

    private static boolean canAdminister(ServerCommandSource source) {
        if (source.hasPermissionLevel(2)) {
            return true;
        }
        ServerPlayerEntity player = source.getPlayer();
        return player != null
                && !source.getServer().isDedicated()
                && source.getServer().isHost(player.getPlayerConfigEntry());
    }

    private static String displayEndpoint(URI endpoint) {
        return EndpointPolicy.display(endpoint, CONFIG.get().sharedToken());
    }

    private static int sendPrompt(ServerPlayerEntity player, String prompt) {
        return CLIENT.sendPrompt(player, prompt);
    }
}
