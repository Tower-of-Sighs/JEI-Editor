package cc.sighs.JEIEditor;

import cc.sighs.JEIEditor.editor.EditorIngredient;
import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.EditorSlot;
import cc.sighs.JEIEditor.editor.RecipePatch;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Adapter for standard vanilla cooking recipes. */
final class CookingRecipeEditorAdapter {
    private CookingRecipeEditorAdapter() { }

    static Optional<EditorModel> createModel(RecipeHolder<?> holder, HolderLookup.Provider registries) {
        if (!(holder.value() instanceof AbstractCookingRecipe)) return Optional.empty();
        AbstractCookingRecipe recipe = (AbstractCookingRecipe) holder.value();
        ResourceLocation serializer = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
        if (serializer == null || !supportsSerializer(serializer.toString())) return Optional.empty();
        List<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.size() != 1) return Optional.empty();
        Optional<EditorIngredient> input = RecipeAdapterSupport.simpleIngredient(ingredients.get(0));
        Optional<EditorIngredient> output = RecipeAdapterSupport.simpleStack(recipe.getResultItem(registries));
        if (!input.isPresent() && !RecipeAdapterSupport.isAirIngredient(ingredients.get(0))) {
            return Optional.empty();
        }
        if (!output.isPresent() || recipe.getExperience() < 0.0F
                || recipe.getCookingTime() < 1) return Optional.empty();
        List<EditorSlot> slots = new ArrayList<EditorSlot>();
        slots.add(new EditorSlot("input.0", "input", input.orElse(null)));
        slots.add(new EditorSlot("output", "output", output.get()));
        Map<String, String> properties = new LinkedHashMap<String, String>();
        properties.put("experience", Float.toString(recipe.getExperience()));
        properties.put("cooking_time", Integer.toString(recipe.getCookingTime()));
        return Optional.of(new EditorModel(holder.id().toString(), serializer.toString(),
                RecipeAdapterSupport.fingerprint(holder.id().toString(), serializer.toString(), slots, properties),
                slots, properties));
    }

    static RecipePatch replaceInput(EditorModel model, String slotKey, ItemStack stack) {
        Optional<EditorIngredient> ingredient = RecipeAdapterSupport.simpleStack(stack);
        if (!ingredient.isPresent() || !"input.0".equals(slotKey)) {
            throw new IllegalArgumentException("only simple cooking input can be replaced");
        }
        return RecipeAdapterSupport.slotPatch(model, "input.0", ingredient.get());
    }

    static RecipePatch replaceOutput(EditorModel model, ItemStack stack) {
        Optional<EditorIngredient> ingredient = RecipeAdapterSupport.simpleStack(stack);
        if (!ingredient.isPresent()) {
            throw new IllegalArgumentException("only simple output items can be used");
        }
        return RecipeAdapterSupport.slotPatch(model, "output", ingredient.get());
    }

    static RecipePatch setOutputCount(EditorModel model, int count) {
        if (count < 1 || count > 64) throw new IllegalArgumentException("output count must be between 1 and 64");
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("output.count", Integer.toString(count));
        return new RecipePatch(model.recipeId(), model.serializerId(), model.baseFingerprint(), fields);
    }
    static RecipePatch setExperience(EditorModel model, float value) {
        if (value < 0.0F || value > 1000.0F || Float.isNaN(value) || Float.isInfinite(value)) throw new IllegalArgumentException("experience must be between 0 and 1000");
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>(); fields.put("recipe.experience", Float.toString(value));
        return new RecipePatch(model.recipeId(), model.serializerId(), model.baseFingerprint(), fields);
    }
    static RecipePatch setCookingTime(EditorModel model, int value) {
        if (value < 1 || value > 1000000) throw new IllegalArgumentException("cooking time must be between 1 and 1000000");
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>(); fields.put("recipe.cooking_time", Integer.toString(value));
        return new RecipePatch(model.recipeId(), model.serializerId(), model.baseFingerprint(), fields);
    }
    static boolean supportsSerializer(String serializerId) {
        return "minecraft:smelting".equals(serializerId) || "minecraft:blasting".equals(serializerId)
                || "minecraft:smoking".equals(serializerId) || "minecraft:campfire_cooking".equals(serializerId);
    }

}
