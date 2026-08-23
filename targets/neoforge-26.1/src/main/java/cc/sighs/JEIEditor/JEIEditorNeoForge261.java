package cc.sighs.JEIEditor;

import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@Mod(JEIEditorNeoForge261.MOD_ID)
public final class JEIEditorNeoForge261 {
    public static final String MOD_ID = "jeieditor";

    public JEIEditorNeoForge261(IEventBus modEventBus) {
        modEventBus.addListener((RegisterPayloadHandlersEvent event) -> NeoForge261Network.register(event));
        RecipeEditorCommands.register();
    }
}
