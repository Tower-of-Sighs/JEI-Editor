package cc.sighs.JEIEditor;

import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.WeakHashMap;

/** Prevents overlapping recipe reloads from racing datapack file updates. */
final class RecipeEditCoordinator {
    private static final long MIN_INTERVAL_MS = 500L;
    private static final Map<MinecraftServer, Long> active = new WeakHashMap<MinecraftServer, Long>();

    private RecipeEditCoordinator() {
    }

    static synchronized boolean tryBegin(MinecraftServer server) {
        long now = System.currentTimeMillis();
        Long previous = active.get(server);
        if (previous != null && (previous.longValue() == 0L || now - previous.longValue() < MIN_INTERVAL_MS)) {
            return false;
        }
        active.put(server, Long.valueOf(0L));
        return true;
    }

    static synchronized void finish(MinecraftServer server) {
        active.put(server, Long.valueOf(System.currentTimeMillis()));
    }
}
