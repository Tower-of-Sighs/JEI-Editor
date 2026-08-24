package cc.sighs.JEIEditor;

import cc.sighs.JEIEditor.editor.EditorModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Optional;

/** Creates a stable identity for deletion without requiring editable slots. */
final class RecipeDeletionAdapter {
    private RecipeDeletionAdapter() {
    }

    static Optional<EditorModel> createModel(RecipeHolder<?> holder) {
        if (holder == null) {
            return Optional.empty();
        }
        ResourceLocation serializerId = BuiltInRegistries.RECIPE_SERIALIZER
                .getKey(holder.value().getSerializer());
        if (serializerId == null) {
            return Optional.empty();
        }
        String fingerprint = fingerprint(holder.id(), serializerId);
        return Optional.of(new EditorModel(holder.id().toString(), serializerId.toString(), fingerprint,
                Collections.emptyList()));
    }

    static boolean matches(RecipeHolder<?> holder, String serializerId, String fingerprint) {
        return createModel(holder)
                .map(model -> model.serializerId().equals(serializerId)
                        && model.baseFingerprint().equals(fingerprint))
                .orElse(false);
    }

    private static String fingerprint(ResourceLocation recipeId, ResourceLocation serializerId) {
        String source = recipeId + "|" + serializerId;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte current : digest) {
                hex.append(String.format("%02x", current & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
