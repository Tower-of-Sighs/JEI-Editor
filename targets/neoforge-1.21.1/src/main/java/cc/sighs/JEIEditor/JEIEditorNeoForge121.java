package cc.sighs.JEIEditor;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import cc.sighs.JEIEditor.server.RecipeEditorCommands;
import cc.sighs.JEIEditor.platform.network.NeoForge121Network;

@Mod(JEIEditorNeoForge121.MOD_ID)
public final class JEIEditorNeoForge121 {
    public static final String MOD_ID = "jeieditor";

    public JEIEditorNeoForge121(IEventBus modEventBus) {
        modEventBus.addListener((RegisterPayloadHandlersEvent event) -> NeoForge121Network.register(event));
        RecipeEditorCommands.register();
    }
}
