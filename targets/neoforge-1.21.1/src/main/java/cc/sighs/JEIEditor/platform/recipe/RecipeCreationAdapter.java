package cc.sighs.JEIEditor.platform.recipe;

import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.EditorSlot;
import cc.sighs.JEIEditor.editor.RecipePatch;
import cc.sighs.JEIEditor.recipe.RecipeCreationRules;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** NeoForge bridge for registry-backed recipe creation. Draft rules live in common. */
public final class RecipeCreationAdapter {
    public static final String SOURCE_RECIPE_FIELD = RecipeCreationRules.SOURCE_RECIPE_FIELD;

    private RecipeCreationAdapter() {
    }

    public static Optional<EditorModel> createModel(RecipeHolder<?> holder, HolderLookup.Provider registries) {
        if (holder == null || registries == null) {
            return Optional.empty();
        }
        ResourceLocation serializerId = BuiltInRegistries.RECIPE_SERIALIZER.getKey(holder.value().getSerializer());
        if (serializerId == null || !RecipeCreationRules.isCreatableSerializer(serializerId.toString())) {
            return Optional.empty();
        }
        ItemStack result = holder.value().getResultItem(registries);
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(result.getItem());
        if (result.isEmpty() || itemId == null || result.getCount() < 1 || result.getCount() > 64
                || !result.isComponentsPatchEmpty()) {
            return Optional.empty();
        }
        String recipeId = RecipeCreationRules.NAMESPACE + ":new_"
                + UUID.randomUUID().toString().replace("-", "");
        List<EditorSlot> slots = new ArrayList<EditorSlot>();
        if (RecipeCreationRules.isCookingSerializer(serializerId.toString())) {
            slots.add(new EditorSlot("input.0", "input", null));
        } else {
            for (int index = 0; index < 9; index++) {
                slots.add(new EditorSlot("input." + index, "input", null));
            }
        }
        slots.add(new EditorSlot("output", "output", null));
        LinkedHashMap<String, String> properties = new LinkedHashMap<String, String>();
        properties.put("grid_width", "3");
        properties.put("grid_height", "3");
        if (holder.value() instanceof AbstractCookingRecipe) {
            AbstractCookingRecipe cooking = (AbstractCookingRecipe) holder.value();
            properties.put("experience", Float.toString(cooking.getExperience()));
            properties.put("cooking_time", Integer.toString(cooking.getCookingTime()));
        }
        properties.put(SOURCE_RECIPE_FIELD, holder.id().toString());
        properties.put(cc.sighs.JEIEditor.editor.RecipePatchSemantics.CREATED_FIELD,
                cc.sighs.JEIEditor.editor.RecipePatchSemantics.CREATED_VALUE);
        return Optional.of(new EditorModel(recipeId, serializerId.toString(), "new:" + recipeId,
                slots, properties));
    }

    public static Optional<EditorModel> createModel(EditorModel source) {
        return RecipeCreationRules.createModel(source);
    }

    public static RecipePatch createPatch(EditorModel model) {
        return RecipeCreationRules.createPatch(model);
    }

    public static EditorModel modelFromPatch(RecipePatch patch) {
        return RecipeCreationRules.modelFromPatch(patch);
    }

    public static boolean isCreatableSerializer(String serializerId) {
        return RecipeCreationRules.isCreatableSerializer(serializerId);
    }

    public static boolean isCreatedModel(EditorModel model) {
        return RecipeCreationRules.isCreatedModel(model);
    }
}
