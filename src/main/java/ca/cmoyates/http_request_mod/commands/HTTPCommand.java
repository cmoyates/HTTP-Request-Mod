package ca.cmoyates.http_request_mod.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class HTTPCommand {
    private HTTPCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerCommands(dispatcher);
        });
    }

    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                literal("http")
                        // optional: op-only
                        // .requires(src -> src.hasPermissionLevel(2))
                        .then(literal("get")
                                .then(argument("URL", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String urlString = StringArgumentType.getString(ctx, "URL");
                                            return httpGet(ctx.getSource(), urlString);
                                        })
                                )
                        )
        );
    }

    private static int httpGet(ServerCommandSource source, String url) {
        // Reply immediately so the command completes (avoid hanging on network)
        source.sendFeedback(() -> Text.literal("Sending GET request to " + url + "..."), false);

        URI uri;
        try {
            uri = URI.create(url.replace("localhost", "127.0.0.1"));
        } catch (Exception e) {
            source.sendError(Text.literal("Invalid URL: " + url));
            return -1;
        }

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .GET()
                    .version(HttpClient.Version.HTTP_1_1)
                    .uri(uri)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .build();

            // Async so we don't block the server thread
            client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .orTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .whenComplete((response, throwable) -> {
                        // Always bounce back onto the MC server thread before touching game state / messaging
                        source.getServer().execute(() -> {
                            if (throwable != null) {
                                source.sendError(Text.literal(
                                        "An error occurred sending the request. Please ensure the IP address is correct: " + url
                                ));
                                return;
                            }

                            if (response.statusCode() == 200) {
                                source.sendFeedback(() -> Text.literal("GET request to " + url + " successful"), false);
                            } else {
                                source.sendError(Text.literal("GET request to " + url + " failed (status " + response.statusCode() + ")"));
                            }
                        });
                    });
        }

        // Command "started successfully" (actual result comes later)
        return 1;
    }
}
