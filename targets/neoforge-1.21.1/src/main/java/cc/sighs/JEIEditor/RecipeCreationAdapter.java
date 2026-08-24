package cc.sighs.JEIEditor;

import cc.sighs.JEIEditor.editor.EditorIngredient;
import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.EditorSlot;
import cc.sighs.JEIEditor.editor.RecipePatch;
import cc.sighs.JEIEditor.editor.RecipePatchSemantics;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Builds blank recipe drafts from a visible vanilla recipe page. */
final class RecipeCreationAdapter {
    private static final String NAMESPACE = "jeieditor";
    static final String SOURCE_RECIPE_FIELD = "recipe.source";

    private RecipeCreationAdapter() {
    }

    static Optional<EditorModel> createModel(RecipeHolder<?> holder, HolderLookup.Provider registries) {
        if (holder == null || registries == null) {
            return Optional.empty();
        }
        String serializer = BuiltInRegistries.RECIPE_SERIALIZER.getKey(holder.value().getSerializer()) == null
                ? "" : BuiltInRegistries.RECIPE_SERIALIZER.getKey(holder.value().getSerializer()).toString();
        if (!isCreatableSerializer(serializer)) {
            return Optional.empty();
        }
        ItemStack result = holder.value().getResultItem(registries);
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(result.getItem());
        if (result.isEmpty() || itemId == null || result.getCount() < 1 || result.getCount() > 64
                || !result.isComponentsPatchEmpty()) {
            return Optional.empty();
        }
        String recipeId = NAMESPACE + ":new_" + UUID.randomUUID().toString().replace("-", "");
        List<EditorSlot> slots = new ArrayList<EditorSlot>();
        if (isCookingSerializer(serializer)) {
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
        properties.put(RecipePatchSemantics.CREATED_FIELD, RecipePatchSemantics.CREATED_VALUE);
        return Optional.of(new EditorModel(recipeId, serializer, "new:" + recipeId, slots, properties));
    }

    /**
     * Builds a creation template from the editor model already resolved for a
     * visible JEI layout. JEI may expose a recipe object that is not the same
     * instance as the server RecipeHolder, so re-resolving it can incorrectly
     * make an otherwise editable crafting page look unsupported.
     */
    static Optional<EditorModel> createModel(EditorModel source) {
        if (source == null || !isCreatableSerializer(source.serializerId())) {
            return Optional.empty();
        }
        EditorSlot output = source.slots().stream()
                .filter(slot -> "output".equals(slot.role()))
                .findFirst().orElse(null);
        if (output == null) {
            return Optional.empty();
        }

        String recipeId = NAMESPACE + ":new_" + UUID.randomUUID().toString().replace("-", "");
        List<EditorSlot> slots = new ArrayList<EditorSlot>();
        if (isCookingSerializer(source.serializerId())) {
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
        if (isCookingSerializer(source.serializerId())) {
            properties.put("experience", source.properties().getOrDefault("experience", "0.0"));
            properties.put("cooking_time", source.properties().getOrDefault("cooking_time", "200"));
        }
        properties.put(SOURCE_RECIPE_FIELD, source.recipeId());
        properties.put(RecipePatchSemantics.CREATED_FIELD, RecipePatchSemantics.CREATED_VALUE);
        return Optional.of(new EditorModel(recipeId, source.serializerId(), "new:" + recipeId,
                slots, properties));
    }

    static RecipePatch createPatch(EditorModel model) {
        if (model == null || !isCreatableSerializer(model.serializerId())) {
            throw new IllegalArgumentException("this page cannot create a recipe");
        }
        EditorSlot output = model.slots().stream()
                .filter(slot -> "output".equals(slot.role()))
                .findFirst().orElse(null);
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        fields.put(RecipePatchSemantics.CREATED_FIELD, RecipePatchSemantics.CREATED_VALUE);
        for (EditorSlot slot : model.slots()) {
            if ("input".equals(slot.role())) {
                fields.put(slot.key() + ".item", "minecraft:air");
                fields.put(slot.key() + ".count", "0");
            }
        }
        // Keep the output explicitly empty until the user places one. The
        // server will reject an empty creation on Save, but the client can
        // still present and edit the complete blank recipe first.
        fields.put("output.item", output == null || output.ingredient() == null
                ? "minecraft:air" : output.ingredient().itemId());
        fields.put("output.count", output == null || output.ingredient() == null
                ? "0" : Integer.toString(output.ingredient().count()));
        if (isCookingSerializer(model.serializerId())) {
            fields.put("recipe.experience", model.properties().getOrDefault("experience", "0.0"));
            fields.put("recipe.cooking_time", model.properties().getOrDefault("cooking_time", "200"));
        }
        return new RecipePatch(model.recipeId(), model.serializerId(), model.baseFingerprint(), fields);
    }

    static EditorModel modelFromPatch(RecipePatch patch) {
        ResourceLocation output = ResourceLocation.tryParse(patch.fields().get("output.item"));
        int count;
        try {
            count = Integer.parseInt(patch.fields().get("output.count"));
        } catch (RuntimeException exception) {
            count = 1;
        }
        List<EditorSlot> slots = new ArrayList<EditorSlot>();
        if (isCookingSerializer(patch.serializerId())) {
            slots.add(new EditorSlot("input.0", "input", null));
        } else {
            for (int index = 0; index < 9; index++) {
                slots.add(new EditorSlot("input." + index, "input", null));
            }
        }
        if (output != null && BuiltInRegistries.ITEM.containsKey(output)) {
            slots.add(new EditorSlot("output", "output", new EditorIngredient(output.toString(), count)));
        }
        LinkedHashMap<String, String> properties = new LinkedHashMap<String, String>();
        properties.put("grid_width", "3");
        properties.put("grid_height", "3");
        properties.put(RecipePatchSemantics.CREATED_FIELD, RecipePatchSemantics.CREATED_VALUE);
        if (patch.fields().containsKey("recipe.experience")) {
            properties.put("experience", patch.fields().get("recipe.experience"));
        }
        if (patch.fields().containsKey("recipe.cooking_time")) {
            properties.put("cooking_time", patch.fields().get("recipe.cooking_time"));
        }
        return new EditorModel(patch.recipeId(), patch.serializerId(), patch.baseFingerprint(), slots, properties);
    }

    static boolean isCreatableSerializer(String serializerId) {
        return "minecraft:crafting_shaped".equals(serializerId)
                || "minecraft:crafting_shapeless".equals(serializerId)
                || isCookingSerializer(serializerId);
    }

    private static boolean isCookingSerializer(String serializerId) {
        return CookingRecipeEditorAdapter.supportsSerializer(serializerId);
    }

    static boolean isCreatedModel(EditorModel model) {
        return model != null && RecipePatchSemantics.CREATED_VALUE.equals(
                model.properties().get(RecipePatchSemantics.CREATED_FIELD));
    }
}
