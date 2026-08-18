package ca.cmoyates.http_request_mod;

import ca.cmoyates.http_request_mod.commands.HTTPCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import com.mojang.brigadier.arguments.StringArgumentType;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class Http_request_mod implements ModInitializer {

    @Override
    public void onInitialize() {
        System.out.println("Initializing");

        HTTPCommand.register();
    }
}
