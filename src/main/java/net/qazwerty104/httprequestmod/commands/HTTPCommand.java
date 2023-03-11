package net.qazwerty104.httprequestmod.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.MessageArgument;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;


public class HTTPCommand {
    public HTTPCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("http")
                .then(Commands.literal("get")
                        .then(Commands.argument("URL", MessageArgument.message())
                                .executes((command) -> {
            MessageArgument.ChatMessage urlMessage = MessageArgument.getChatMessage(command, "URL");
            String urlString = urlMessage.signedArgument().signedBody().content().plain();
            urlString = urlString.replace("localhost", "127.0.0.1");
            return http(command.getSource(), "GET", urlString);
        }))));
    }

    private int http(CommandSourceStack source, String requestType, String URL) throws CommandSyntaxException {


        try {
            var uri = URI.create(URL);
            var client = HttpClient.newHttpClient();
            var request = HttpRequest.newBuilder()
                    .GET()
                    .version(HttpClient.Version.HTTP_1_1)
                    .uri(uri)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .build();

            var response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            if (response.statusCode() == 200) {
                source.sendSuccess(Component.literal(
                        "GET request to " + URL + " successful"
                ), true);
                return 1;
            }
            else {
                source.sendFailure(Component.literal(
                        "GET request to " + URL + " failed"
                ));

                return -1;
            }
        }
        catch (Exception e) {
            //source.sendFailure(Component.literal(
            //        "An error occurred sending the request. Please ensure the IP address is correct: " + ip
            //));
            source.sendFailure(Component.literal(
                    e.toString() + ": " +
                            e.getMessage()
            ));

            System.out.println(e.toString() + ": " +
                    e.getMessage());

            e.printStackTrace();

            return -1;
        }
    }
}
