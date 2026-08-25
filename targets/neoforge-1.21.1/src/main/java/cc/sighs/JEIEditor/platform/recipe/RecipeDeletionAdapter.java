package cc.sighs.JEIEditor.platform.recipe;

import cc.sighs.JEIEditor.editor.EditorModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.Collections;
import java.util.Optional;

/** Creates a stable identity for deletion without requiring editable slots. */
public final class RecipeDeletionAdapter {
    private RecipeDeletionAdapter() {
    }

    public static Optional<EditorModel> createModel(RecipeHolder<?> holder) {
        if (holder == null) {
            return Optional.empty();
        }
        ResourceLocation serializerId = BuiltInRegistries.RECIPE_SERIALIZER
                .getKey(holder.value().getSerializer());
        if (serializerId == null) {
            return Optional.empty();
        }
        String fingerprint = RecipeAdapterSupport.fingerprint(holder.id().toString(), serializerId.toString(),
                Collections.emptyList(), Collections.emptyMap());
        return Optional.of(new EditorModel(holder.id().toString(), serializerId.toString(), fingerprint,
                Collections.emptyList()));
    }

    public static boolean matches(RecipeHolder<?> holder, String serializerId, String fingerprint) {
        return createModel(holder)
                .map(model -> model.serializerId().equals(serializerId)
                        && model.baseFingerprint().equals(fingerprint))
                .orElse(false);
    }

}
