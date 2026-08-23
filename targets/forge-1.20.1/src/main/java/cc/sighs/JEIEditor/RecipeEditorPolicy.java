package cc.sighs.JEIEditor;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

final class RecipeEditorPolicy {
    private static final int DEFAULT_PERMISSION_LEVEL = 2;
    private final int minPermissionLevel;
    private final Set<String> allowNamespaces;
    private final Set<String> denyNamespaces;

    private RecipeEditorPolicy(int minPermissionLevel, Set<String> allowNamespaces, Set<String> denyNamespaces) {
        this.minPermissionLevel = minPermissionLevel;
        this.allowNamespaces = allowNamespaces;
        this.denyNamespaces = denyNamespaces;
    }

    static RecipeEditorPolicy load(MinecraftServer server) {
        Properties properties = new Properties();
        Path file = server.getServerDirectory().toPath().resolve("config").resolve("jeieditor.properties");
        try {
            Files.createDirectories(file.getParent());
            if (Files.notExists(file)) {
                properties.setProperty("min_permission_level", Integer.toString(DEFAULT_PERMISSION_LEVEL));
                properties.setProperty("allow_namespaces", "");
                properties.setProperty("deny_namespaces", "");
                try (java.io.Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    properties.store(writer, "JEI Editor server policy");
                }
            } else {
                try (java.io.Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
            }
        } catch (IOException ignored) {
            return new RecipeEditorPolicy(DEFAULT_PERMISSION_LEVEL, Collections.<String>emptySet(), Collections.<String>emptySet());
        }
        int permission = parsePermission(properties.getProperty("min_permission_level"));
        return new RecipeEditorPolicy(permission, parseNamespaces(properties.getProperty("allow_namespaces")),
                parseNamespaces(properties.getProperty("deny_namespaces")));
    }

    boolean canEdit(ServerPlayer player, String recipeId) {
        return player != null && player.hasPermissions(minPermissionLevel) && isAllowed(recipeId);
    }

    boolean canEdit(CommandSourceStack source, String recipeId) {
        return source != null && source.hasPermission(minPermissionLevel) && isAllowed(recipeId);
    }
    boolean canUseCommands(CommandSourceStack source) { return source != null && source.hasPermission(minPermissionLevel); }

    boolean isAllowed(String recipeId) {
        ResourceLocation id = ResourceLocation.tryParse(recipeId);
        if (id == null) return false;
        String namespace = id.getNamespace();
        if (denyNamespaces.contains(namespace)) return false;
        return allowNamespaces.isEmpty() || allowNamespaces.contains(namespace);
    }

    private static int parsePermission(String value) {
        try { return Math.max(0, Math.min(4, Integer.parseInt(value.trim()))); }
        catch (Exception ignored) { return DEFAULT_PERMISSION_LEVEL; }
    }

    private static Set<String> parseNamespaces(String value) {
        Set<String> result = new HashSet<String>();
        if (value == null) return result;
        for (String token : value.split(",")) {
            String namespace = token.trim().toLowerCase(Locale.ROOT);
            if (namespace.matches("[a-z0-9_.-]+")) result.add(namespace);
        }
        return Collections.unmodifiableSet(result);
    }
}
