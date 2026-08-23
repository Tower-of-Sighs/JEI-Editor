package cc.sighs.JEIEditor;

import cc.sighs.JEIEditor.editor.EditorIngredient;
import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.EditorSlot;
import cc.sighs.JEIEditor.editor.RecipePatch;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

final class CraftingRecipeEditorAdapter {
    private CraftingRecipeEditorAdapter() { }

    static Optional<EditorModel> createModel(ResourceLocation recipeId, Recipe<?> recipe, RegistryAccess registries) {
        if (!(recipe instanceof ShapedRecipe) && !(recipe instanceof ShapelessRecipe)) return Optional.empty();
        ResourceLocation serializerId = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
        if (serializerId == null) return Optional.empty();
        List<EditorSlot> slots = new ArrayList<EditorSlot>();
        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        for (int i = 0; i < ingredients.size(); i++) {
            Optional<EditorIngredient> ingredient = simpleIngredient(ingredients.get(i));
            if (!ingredients.get(i).isEmpty() && !ingredient.isPresent()) return Optional.empty();
            slots.add(new EditorSlot("input." + i, "input", ingredient.orElse(null)));
        }
        Optional<EditorIngredient> output = simpleStack(recipe.getResultItem(registries));
        if (!output.isPresent()) return Optional.empty();
        slots.add(new EditorSlot("output", "output", output.get()));
        return Optional.of(new EditorModel(recipeId.toString(), serializerId.toString(), fingerprint(recipeId, serializerId, slots), slots));
    }

    static RecipePatch replaceInput(EditorModel model, String key, ItemStack stack) {
        Optional<EditorIngredient> ingredient = simpleStack(stack);
        if (!ingredient.isPresent() || !key.startsWith("input.")) throw new IllegalArgumentException("only simple input slots can be replaced");
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        fields.put(key + ".item", ingredient.get().itemId());
        fields.put(key + ".count", Integer.toString(ingredient.get().count()));
        return new RecipePatch(model.recipeId(), model.serializerId(), model.baseFingerprint(), fields);
    }

    static RecipePatch clearSlot(EditorModel model, String key) {
        if (!"minecraft:crafting_shaped".equals(model.serializerId()) || !key.startsWith("input.")) throw new IllegalArgumentException("only shaped crafting input slots can be cleared");
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        fields.put(key + ".item", "minecraft:air");
        fields.put(key + ".count", "0");
        return new RecipePatch(model.recipeId(), model.serializerId(), model.baseFingerprint(), fields);
    }

    static RecipePatch setOutputCount(EditorModel model, int count) {
        if (count < 1 || count > 64) throw new IllegalArgumentException("output count must be between 1 and 64");
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("output.count", Integer.toString(count));
        return new RecipePatch(model.recipeId(), model.serializerId(), model.baseFingerprint(), fields);
    }

    private static Optional<EditorIngredient> simpleIngredient(Ingredient ingredient) {
        if (ingredient == null || ingredient.isEmpty()) return Optional.empty();
        ItemStack[] items = ingredient.getItems();
        return items.length == 1 ? simpleStack(items[0]) : Optional.<EditorIngredient>empty();
    }

    private static Optional<EditorIngredient> simpleStack(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getCount() < 1 || stack.getCount() > 64 || stack.hasTag()) return Optional.empty();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? Optional.<EditorIngredient>empty() : Optional.of(new EditorIngredient(id.toString(), stack.getCount()));
    }

    private static String fingerprint(ResourceLocation id, ResourceLocation serializer, List<EditorSlot> slots) {
        StringBuilder value = new StringBuilder(id.toString()).append('|').append(serializer);
        for (EditorSlot slot : slots) {
            value.append('|').append(slot.key()).append('=').append(slot.role());
            if (slot.ingredient() != null) value.append(':').append(slot.ingredient().itemId()).append(':').append(slot.ingredient().count());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte current : digest) result.append(String.format("%02x", current & 0xff));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
