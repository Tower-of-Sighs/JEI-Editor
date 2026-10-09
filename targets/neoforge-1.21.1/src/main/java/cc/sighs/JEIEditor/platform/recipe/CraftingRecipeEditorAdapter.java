package cc.sighs.JEIEditor.platform.recipe;

import cc.sighs.JEIEditor.editor.EditorIngredient;
import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.EditorSlot;
import cc.sighs.JEIEditor.editor.RecipePatch;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import mezz.jei.library.gui.helpers.CraftingGridHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Adapter for vanilla crafting recipes. Tag ingredients use a representative
 * item in the client model and are preserved until that slot is edited.
 *
 * <p>Its scope is the two vanilla crafting serializers, not "any recipe whose
 * class extends ShapedRecipe or ShapelessRecipe" - see {@link #supportsSerializer}. */
public final class CraftingRecipeEditorAdapter {
    private CraftingRecipeEditorAdapter() {
    }

    public static Optional<EditorModel> createModel(RecipeHolder<?> holder, HolderLookup.Provider registries) {
        Recipe<?> recipe = holder.value();
        if (!(recipe instanceof ShapedRecipe) && !(recipe instanceof ShapelessRecipe)) {
            return Optional.empty();
        }

        ResourceLocation serializerId = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
        // The Java class alone is deliberately not enough. Mods ship their own serializers
        // whose recipe class merely extends ShapedRecipe/ShapelessRecipe - Refined
        // Storage's "refinedstorage:recoloring" (its codec is
        // {ingredient, dye, result} and has no "ingredients" array at all) and Silent
        // Gear's "silentgear:compound_part" are two in this pack - and the write path
        // rebuilds a vanilla {"type","category","pattern"/"ingredients","key","result"}
        // object that such a codec need not accept. Only claim the serializers this
        // adapter really implements, so a modded one is left unmodelable instead of
        // being offered for editing and then refused (or worse, written) by the server.
        if (serializerId == null || !supportsSerializer(serializerId.toString())) {
            return Optional.empty();
        }

        List<EditorSlot> slots = new ArrayList<EditorSlot>();
        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        if (recipe instanceof ShapedRecipe) {
            // The editor uses stable workbench coordinates rather than the
            // compact ingredient list exposed by ShapedRecipe. This keeps
            // input.0..input.8 available even when the source pattern is 2x3
            // (or otherwise leaves the right/bottom cells empty).
            ShapedRecipe shaped = (ShapedRecipe) recipe;
            List<EditorIngredient> grid = new ArrayList<EditorIngredient>(9);
            for (int i = 0; i < 9; i++) {
                grid.add(null);
            }
            for (int i = 0; i < ingredients.size(); i++) {
                Ingredient source = ingredients.get(i);
                if (!source.isEmpty() && !RecipeAdapterSupport.simpleIngredient(source).isPresent()
                        && !RecipeAdapterSupport.isAirIngredient(source)) {
                    return Optional.empty();
                }
                int gridIndex = CraftingSlotMapper.craftingGridIndex(
                        i, shaped.getWidth(), shaped.getHeight());
                if (gridIndex < 0 || gridIndex >= grid.size()) {
                    return Optional.empty();
                }
                grid.set(gridIndex, RecipeAdapterSupport.simpleIngredient(source).orElse(null));
            }
            for (int i = 0; i < grid.size(); i++) {
                slots.add(new EditorSlot("input." + i, "input", grid.get(i)));
            }
        } else {
            // JEI always lays crafting inputs out in a 3x3 grid, even when a
            // shapeless recipe only uses one or two cells. Keep all nine
            // semantic cells editable so an empty cell can receive an item.
            @SuppressWarnings("unchecked")
            RecipeHolder<CraftingRecipe> craftingHolder =
                    (RecipeHolder<CraftingRecipe>) (RecipeHolder<?>) holder;
            Map<Integer, Ingredient> gridIngredients = CraftingGridHelper
                    .getGuiSlotToIngredientMap(craftingHolder, 0, 0);
            for (int gridIndex = 0; gridIndex < 9; gridIndex++) {
                Ingredient source = gridIngredients.get(Integer.valueOf(gridIndex));
                if (source != null && !source.isEmpty()
                        && !RecipeAdapterSupport.simpleIngredient(source).isPresent()
                        && !RecipeAdapterSupport.isAirIngredient(source)) {
                    return Optional.empty();
                }
                slots.add(new EditorSlot("input." + gridIndex, "input",
                        source == null ? null : RecipeAdapterSupport.simpleIngredient(source).orElse(null)));
            }
        }

        ItemStack result = recipe.getResultItem(registries);
        Optional<EditorIngredient> output = RecipeAdapterSupport.simpleStack(result);
        if (!output.isPresent()) {
            return Optional.empty();
        }
        slots.add(new EditorSlot("output", "output", output.get()));

        String fingerprint = RecipeAdapterSupport.fingerprint(holder.id().toString(), serializerId.toString(),
                slots, Collections.<String, String>emptyMap());
        return Optional.of(new EditorModel(holder.id().toString(), serializerId.toString(), fingerprint, slots));
    }

    /** The serializers this adapter implements: the vanilla crafting pair. */
    public static boolean supportsSerializer(String serializerId) {
        return "minecraft:crafting_shaped".equals(serializerId)
                || "minecraft:crafting_shapeless".equals(serializerId);
    }

    public static RecipePatch replaceInput(EditorModel model, String slotKey, ItemStack stack) {
        Optional<EditorIngredient> ingredient = RecipeAdapterSupport.simpleStack(stack);
        if (!ingredient.isPresent() || !slotKey.startsWith("input.")
                || !supportsSerializer(model == null ? null : model.serializerId())) {
            throw new IllegalArgumentException("only simple input slots can be replaced");
        }
        return RecipeAdapterSupport.slotPatch(model, slotKey, ingredient.get());
    }

    public static RecipePatch replaceOutput(EditorModel model, ItemStack stack) {
        Optional<EditorIngredient> ingredient = RecipeAdapterSupport.simpleStack(stack);
        if (!ingredient.isPresent() || !supportsSerializer(model == null ? null : model.serializerId())) {
            throw new IllegalArgumentException("only simple output items can be used");
        }
        return RecipeAdapterSupport.slotPatch(model, "output", ingredient.get());
    }

    public static RecipePatch clearSlot(EditorModel model, String slotKey) {
        if (!supportsSerializer(model == null ? null : model.serializerId()) || !slotKey.startsWith("input.")) {
            throw new IllegalArgumentException("only crafting input slots can be cleared");
        }
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        fields.put(slotKey + ".item", "minecraft:air");
        fields.put(slotKey + ".count", "0");
        return new RecipePatch(model.recipeId(), model.serializerId(), model.baseFingerprint(), fields);
    }

    public static RecipePatch setOutputCount(EditorModel model, int count) {
        if (count < 1 || count > 64) {
            throw new IllegalArgumentException("output count must be between 1 and 64");
        }
        if (!supportsSerializer(model == null ? null : model.serializerId())) {
            throw new IllegalArgumentException("this page has no editable output");
        }
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("output.count", Integer.toString(count));
        return new RecipePatch(model.recipeId(), model.serializerId(), model.baseFingerprint(), fields);
    }

    public static EditorModel withGridDimensions(EditorModel model, int width, int height) {
        if (!"minecraft:crafting_shaped".equals(model.serializerId())
                || model.properties().containsKey("grid_width")) {
            return model;
        }
        LinkedHashMap<String, String> properties = new LinkedHashMap<String, String>(model.properties());
        properties.put("grid_width", Integer.toString(width));
        properties.put("grid_height", Integer.toString(height));
        return new EditorModel(model.recipeId(), model.serializerId(), model.baseFingerprint(),
                model.slots(), properties);
    }

}
