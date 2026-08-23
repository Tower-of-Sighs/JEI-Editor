package cc.sighs.JEIEditor;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@Mod(JEIEditorNeoForge121.MOD_ID)
public final class JEIEditorNeoForge121 {
    public static final String MOD_ID = "jeieditor";

    public JEIEditorNeoForge121(IEventBus modEventBus) {
        modEventBus.addListener((RegisterPayloadHandlersEvent event) -> NeoForge121Network.register(event));
        RecipeEditorCommands.register();
    }
}
