package ca.cmoyates.http_request_mod;

import ca.cmoyates.http_request_mod.commands.HTTPCommand;
import ca.cmoyates.http_request_mod.commands.WebSocketCommand;
import net.fabricmc.api.ModInitializer;

public class Http_request_mod implements ModInitializer {

    @Override
    public void onInitialize() {
        System.out.println("Initializing");

        HTTPCommand.register();
        WebSocketCommand.register();
    }
}
