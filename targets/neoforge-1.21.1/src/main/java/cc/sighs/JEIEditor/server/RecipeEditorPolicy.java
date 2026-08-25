package cc.sighs.JEIEditor.server;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import cc.sighs.JEIEditor.recipe.RecipePolicyRules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class RecipeEditorPolicy {
    private final RecipePolicyRules rules;

    private RecipeEditorPolicy(RecipePolicyRules rules) {
        this.rules = rules;
    }
    public static RecipeEditorPolicy load(MinecraftServer server) {
        Properties properties = new Properties();
        Path file = server.getServerDirectory().resolve("config").resolve("jeieditor.properties");
        try {
            Files.createDirectories(file.getParent());
            if (Files.notExists(file)) {
                properties.setProperty("min_permission_level", "2"); properties.setProperty("allow_namespaces", ""); properties.setProperty("deny_namespaces", "");
                try (java.io.Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) { properties.store(writer, "JEI Editor server policy"); }
            } else try (java.io.Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
        } catch (IOException ignored) {
            return new RecipeEditorPolicy(RecipePolicyRules.fromProperties(new Properties()));
        }
        return new RecipeEditorPolicy(RecipePolicyRules.fromProperties(properties));
    }
    public boolean canEdit(ServerPlayer player, String recipeId) {
        return player != null && player.hasPermissions(rules.minPermissionLevel()) && isAllowed(recipeId);
    }
    public boolean canEdit(CommandSourceStack source, String recipeId) {
        return source != null && source.hasPermission(rules.minPermissionLevel()) && isAllowed(recipeId);
    }
    public boolean canUseCommands(CommandSourceStack source) {
        return source != null && source.hasPermission(rules.minPermissionLevel());
    }
    public boolean isAllowed(String recipeId) {
        return rules.isAllowed(recipeId);
    }
}
