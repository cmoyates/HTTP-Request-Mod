package net.qazwerty104.httprequestmod.events;

        import net.minecraftforge.event.RegisterCommandsEvent;
        import net.minecraftforge.eventbus.api.SubscribeEvent;
        import net.minecraftforge.fml.common.Mod;
        import net.minecraftforge.server.command.ConfigCommand;
        import net.qazwerty104.httprequestmod.HTTPRequestMod;
        import net.qazwerty104.httprequestmod.commands.HTTPCommand;

@Mod.EventBusSubscriber(modid = HTTPRequestMod.MOD_ID)
public class ModEvents {

    @SubscribeEvent
    public static void onCommandsRegister(RegisterCommandsEvent event) {
        new HTTPCommand(event.getDispatcher());

        ConfigCommand.register(event.getDispatcher());
    }

}
