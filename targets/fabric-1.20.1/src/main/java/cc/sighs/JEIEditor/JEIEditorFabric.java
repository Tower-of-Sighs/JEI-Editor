package cc.sighs.JEIEditor;

import net.fabricmc.api.ModInitializer;

public final class JEIEditorFabric implements ModInitializer {
    public static final String MOD_ID = "jeieditor";

    @Override
    public void onInitialize() {
        FabricNetwork.registerServer();
        RecipeEditorCommands.register();
    }
}
