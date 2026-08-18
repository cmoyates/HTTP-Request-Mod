package ca.cmoyates.http_request_mod.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class HTTPCommand {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Set<String> HTTP_SCHEMES = Set.of("http", "https");
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private HTTPCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerCommands(dispatcher);
        });
    }

    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                literal("http")
                        // Datapack functions execute with sufficient permission; players need operator access.
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(literal("get")
                                .then(literal("storage")
                                        .then(argument("storage", StringArgumentType.greedyString())
                                                .executes(ctx -> httpGetFromStorage(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "storage")
                                                ))
                                        )
                                )
                                .then(argument("URL", StringArgumentType.greedyString())
                                        .executes(ctx -> httpGet(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "URL")
                                        ))
                                )
                        )
        );
    }

    private static int httpGetFromStorage(ServerCommandSource source, String storageName) {
        Identifier storageId = Identifier.tryParse(storageName);
        if (storageId == null) {
            source.sendError(Text.literal("Invalid storage ID: " + storageName));
            return -1;
        }

        NbtCompound storage = source.getServer().getDataCommandStorage().get(storageId);
        String url = storage.getString("url").orElse(null);
        if (url == null) {
            source.sendError(Text.literal("Storage " + storageId + " must contain a string named url"));
            return -1;
        }

        return httpGet(source, url);
    }

    private static int httpGet(ServerCommandSource source, String url) {
        // Reply immediately so the command completes (avoid hanging on network)
        source.sendFeedback(() -> Text.literal("Sending GET request to " + url + "..."), false);

        URI uri;
        try {
            uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null
                    || !HTTP_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException("URL must be an absolute HTTP(S) URL");
            }
        } catch (IllegalArgumentException exception) {
            source.sendError(Text.literal("Invalid HTTP(S) URL: " + url));
            return -1;
        }

        HttpRequest request = HttpRequest.newBuilder()
                .GET()
                .uri(uri)
                .header("Accept", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .build();

        // The client is shared for the life of the server; closing a per-request client can block here.
        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .orTimeout(REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                .whenComplete((response, throwable) -> {
                    // Always bounce back onto the MC server thread before touching game state / messaging.
                    source.getServer().execute(() -> {
                        if (throwable != null) {
                            source.sendError(Text.literal(
                                    "GET request to " + url + " failed: " + throwable.getMessage()
                            ));
                            return;
                        }

                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            source.sendFeedback(() -> Text.literal("GET request to " + url + " successful"), false);
                        } else {
                            source.sendError(Text.literal("GET request to " + url + " failed (status " + response.statusCode() + ")"));
                        }
                    });
                });

        // Command "started successfully" (actual result comes later)
        return 1;
    }
}
