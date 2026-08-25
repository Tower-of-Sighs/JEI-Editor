package cc.sighs.JEIEditor.recipe;

import cc.sighs.JEIEditor.editor.EditorIngredient;
import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.EditorSlot;
import cc.sighs.JEIEditor.editor.RecipePatch;
import cc.sighs.JEIEditor.editor.RecipePatchSemantics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Loader-independent rules for creating blank recipe drafts. */
public final class RecipeCreationRules {
    public static final String NAMESPACE = "jeieditor";
    public static final String SOURCE_RECIPE_FIELD = "recipe.source";

    private RecipeCreationRules() {
    }

    public static Optional<EditorModel> createModel(EditorModel source) {
        if (source == null || !isCreatableSerializer(source.serializerId())) {
            return Optional.empty();
        }
        boolean hasOutput = false;
        for (EditorSlot slot : source.slots()) {
            if ("output".equals(slot.role())) {
                hasOutput = true;
                break;
            }
        }
        if (!hasOutput) {
            return Optional.empty();
        }

        String recipeId = NAMESPACE + ":new_" + UUID.randomUUID().toString().replace("-", "");
        List<EditorSlot> slots = blankSlots(source.serializerId());
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

    public static RecipePatch createPatch(EditorModel model) {
        if (model == null || !isCreatableSerializer(model.serializerId())) {
            throw new IllegalArgumentException("this page cannot create a recipe");
        }
        EditorSlot output = null;
        for (EditorSlot slot : model.slots()) {
            if ("output".equals(slot.role())) {
                output = slot;
                break;
            }
        }
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        fields.put(RecipePatchSemantics.CREATED_FIELD, RecipePatchSemantics.CREATED_VALUE);
        for (EditorSlot slot : model.slots()) {
            if ("input".equals(slot.role())) {
                fields.put(slot.key() + ".item", "minecraft:air");
                fields.put(slot.key() + ".count", "0");
            }
        }
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

    /** Reconstructs a creation model without consulting a platform registry. */
    public static EditorModel modelFromPatch(RecipePatch patch) {
        if (patch == null || !isCreatableSerializer(patch.serializerId())) {
            throw new IllegalArgumentException("unsupported creation patch");
        }
        List<EditorSlot> slots = blankSlots(patch.serializerId());
        String outputId = patch.fields().get("output.item");
        int count = parseCount(patch.fields().get("output.count"));
        if (outputId != null && count > 0 && !"minecraft:air".equals(outputId)) {
            try {
                slots.set(slots.size() - 1, new EditorSlot("output", "output",
                        new EditorIngredient(outputId, count)));
            } catch (IllegalArgumentException ignored) {
                // The platform adapter performs registry validation before use.
            }
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

    public static boolean isCreatableSerializer(String serializerId) {
        return "minecraft:crafting_shaped".equals(serializerId)
                || "minecraft:crafting_shapeless".equals(serializerId)
                || isCookingSerializer(serializerId);
    }

    public static boolean isCookingSerializer(String serializerId) {
        return "minecraft:smelting".equals(serializerId)
                || "minecraft:blasting".equals(serializerId)
                || "minecraft:smoking".equals(serializerId)
                || "minecraft:campfire_cooking".equals(serializerId);
    }

    public static boolean isCreatedModel(EditorModel model) {
        return model != null && RecipePatchSemantics.CREATED_VALUE.equals(
                model.properties().get(RecipePatchSemantics.CREATED_FIELD));
    }

    private static List<EditorSlot> blankSlots(String serializerId) {
        List<EditorSlot> slots = new ArrayList<EditorSlot>();
        if (isCookingSerializer(serializerId)) {
            slots.add(new EditorSlot("input.0", "input", null));
        } else {
            for (int index = 0; index < 9; index++) {
                slots.add(new EditorSlot("input." + index, "input", null));
            }
        }
        slots.add(new EditorSlot("output", "output", null));
        return slots;
    }

    private static int parseCount(String value) {
        try {
            return Integer.parseInt(value);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }
}
