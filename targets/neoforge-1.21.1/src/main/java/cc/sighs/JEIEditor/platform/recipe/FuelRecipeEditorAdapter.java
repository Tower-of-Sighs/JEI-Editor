package cc.sighs.JEIEditor.platform.recipe;

import cc.sighs.JEIEditor.JEIEditorNeoForge121;

import cc.sighs.JEIEditor.editor.EditorIngredient;
import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.EditorSlot;
import cc.sighs.JEIEditor.editor.RecipePatch;
import mezz.jei.api.recipe.vanilla.IJeiFuelingRecipe;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/** Adapter for JEI's generated furnace-fuel entries. */
public final class FuelRecipeEditorAdapter {
    public static final String SERIALIZER = "neoforge:furnace_fuel";
    private static final String RECIPE_NAMESPACE = JEIEditorNeoForge121.MOD_ID;
    private static final String RECIPE_PREFIX = "fuel/";

    private FuelRecipeEditorAdapter() {
    }

    public static boolean isFuelRecipe(Object recipe) {
        return recipe instanceof IJeiFuelingRecipe;
    }

    public static Optional<EditorModel> createModel(IJeiFuelingRecipe recipe) {
        if (recipe == null || recipe.getInputs() == null || recipe.getInputs().size() != 1
                || recipe.getBurnTime() < 1) {
            return Optional.empty();
        }
        ItemStack stack = recipe.getInputs().get(0);
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (itemId == null) {
            return Optional.empty();
        }
        return Optional.of(createModel(itemId, recipe.getBurnTime(), stack.getCount()));
    }

    public static EditorModel createModel(ResourceLocation itemId, int burnTime) {
        return createModel(itemId, burnTime, 1);
    }

    private static EditorModel createModel(ResourceLocation itemId, int burnTime, int count) {
        List<EditorSlot> slots = new ArrayList<EditorSlot>();
        slots.add(new EditorSlot("input.0", "input", new EditorIngredient(itemId.toString(),
                Math.max(1, Math.min(64, count)))));
        LinkedHashMap<String, String> properties = new LinkedHashMap<String, String>();
        properties.put("burn_time", Integer.toString(burnTime));
        String recipeId = recipeId(itemId).toString();
        return new EditorModel(recipeId, SERIALIZER,
                RecipeAdapterSupport.fingerprint(recipeId, SERIALIZER, slots, properties), slots, properties);
    }

    public static RecipePatch setBurnTime(EditorModel model, int burnTime) {
        return setBurnTime(model, burnTime, model.baseFingerprint());
    }

    public static RecipePatch setBurnTime(EditorModel model, int burnTime, String baseFingerprint) {
        if (model == null || !SERIALIZER.equals(model.serializerId())) {
            throw new IllegalArgumentException("not a furnace fuel entry");
        }
        if (burnTime < 1 || burnTime > 2_000_000_000) {
            throw new IllegalArgumentException("burn time must be between 1 and 2000000000 ticks");
        }
        EditorSlot input = model.slots().stream()
                .filter(slot -> "input.0".equals(slot.key()))
                .findFirst().orElse(null);
        if (input == null || input.ingredient() == null) {
            throw new IllegalArgumentException("fuel item is missing");
        }
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("input.0.item", input.ingredient().itemId());
        fields.put("fuel.burn_time", Integer.toString(burnTime));
        return new RecipePatch(model.recipeId(), SERIALIZER, baseFingerprint, fields);
    }

    public static boolean isFuelPatch(RecipePatch patch) {
        return patch != null && SERIALIZER.equals(patch.serializerId())
                && patch.fields().containsKey("input.0.item")
                && patch.fields().containsKey("fuel.burn_time");
    }

    public static ResourceLocation recipeId(ResourceLocation itemId) {
        return ResourceLocation.fromNamespaceAndPath(RECIPE_NAMESPACE,
                RECIPE_PREFIX + itemId.getNamespace() + "/" + itemId.getPath());
    }

    public static Optional<ResourceLocation> itemIdFromRecipeId(String recipeId) {
        ResourceLocation id = ResourceLocation.tryParse(recipeId);
        if (id == null || !RECIPE_NAMESPACE.equals(id.getNamespace())
                || !id.getPath().startsWith(RECIPE_PREFIX)) {
            return Optional.empty();
        }
        String encoded = id.getPath().substring(RECIPE_PREFIX.length());
        int separator = encoded.indexOf('/');
        if (separator <= 0 || separator == encoded.length() - 1) {
            return Optional.empty();
        }
        return Optional.ofNullable(ResourceLocation.tryParse(
                encoded.substring(0, separator) + ":" + encoded.substring(separator + 1)));
    }

    public static Optional<ResourceLocation> itemId(RecipePatch patch) {
        if (!isFuelPatch(patch)) {
            return Optional.empty();
        }
        ResourceLocation itemId = ResourceLocation.tryParse(patch.fields().get("input.0.item"));
        return itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)
                ? Optional.<ResourceLocation>empty() : Optional.of(itemId);
    }

    public static int burnTime(RecipePatch patch) {
        try {
            return Integer.parseInt(patch.fields().get("fuel.burn_time"));
        } catch (RuntimeException exception) {
            return -1;
        }
    }

}
