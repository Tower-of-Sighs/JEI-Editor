package cc.sighs.JEIEditor.server;

import net.minecraft.server.MinecraftServer;

import cc.sighs.JEIEditor.recipe.EditOperationCoordinator;

/** Prevents overlapping recipe reloads from racing datapack file updates. */
public final class RecipeEditCoordinator {
    private static final EditOperationCoordinator<MinecraftServer> DELEGATE =
            new EditOperationCoordinator<MinecraftServer>(500L);

    private RecipeEditCoordinator() {
    }

    public static boolean tryBegin(MinecraftServer server) {
        return DELEGATE.tryBegin(server);
    }

    public static void finish(MinecraftServer server) {
        DELEGATE.finish(server);
    }
}
