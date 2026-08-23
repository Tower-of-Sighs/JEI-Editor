package cc.sighs.JEIEditor;

import cc.sighs.JEIEditor.editor.EditorIngredient;
import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.EditorSlot;
import cc.sighs.JEIEditor.editor.RecipePatch;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/** Adapter for simple vanilla crafting recipes. Complex/tag ingredients remain read-only. */
final class CraftingRecipeEditorAdapter {
    private CraftingRecipeEditorAdapter() {
    }

    static Optional<EditorModel> createModel(RecipeHolder<?> holder, HolderLookup.Provider registries) {
        Recipe<?> recipe = holder.value();
        if (!(recipe instanceof ShapedRecipe) && !(recipe instanceof ShapelessRecipe)) {
            return Optional.empty();
        }

        Identifier serializerId = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
        if (serializerId == null) {
            return Optional.empty();
        }

        List<EditorSlot> slots = new ArrayList<EditorSlot>();
        List<java.util.Optional<Ingredient>> ingredients = recipe instanceof ShapedRecipe
                ? ((ShapedRecipe) recipe).getIngredients()
                : java.util.Collections.<java.util.Optional<Ingredient>>emptyList();
        if (recipe instanceof ShapelessRecipe) {
            ingredients = ((ShapelessRecipe) recipe).placementInfo().ingredients().stream()
                    .map(java.util.Optional::of).toList();
        }
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ingredientValue = ingredients.get(i).orElse(null);
            if (ingredientValue != null && !ingredientValue.isEmpty() && !simpleIngredient(ingredientValue).isPresent()) {
                return Optional.empty();
            }
            Optional<EditorIngredient> ingredient = ingredientValue == null
                    ? Optional.<EditorIngredient>empty() : simpleIngredient(ingredientValue);
            slots.add(new EditorSlot("input." + i, "input", ingredient.orElse(null)));
        }

        if (!(recipe instanceof CraftingRecipe)) {
            return Optional.empty();
        }
        ItemStack result = ((CraftingRecipe) recipe).assemble(net.minecraft.world.item.crafting.CraftingInput.EMPTY);
        Optional<EditorIngredient> output = simpleStack(result);
        if (!output.isPresent()) {
            return Optional.empty();
        }
        slots.add(new EditorSlot("output", "output", output.get()));

        String fingerprint = fingerprint(holder.id().identifier(), serializerId, slots);
        return Optional.of(new EditorModel(holder.id().identifier().toString(), serializerId.toString(), fingerprint, slots));
    }

    static RecipePatch replaceInput(EditorModel model, String slotKey, ItemStack stack) {
        Optional<EditorIngredient> ingredient = simpleStack(stack);
        if (!ingredient.isPresent() || !slotKey.startsWith("input.")) {
            throw new IllegalArgumentException("only simple input slots can be replaced");
        }
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        fields.put(slotKey + ".item", ingredient.get().itemId());
        fields.put(slotKey + ".count", Integer.toString(ingredient.get().count()));
        return new RecipePatch(model.recipeId(), model.serializerId(), model.baseFingerprint(), fields);
    }

    static RecipePatch clearSlot(EditorModel model, String slotKey) {
        if (!"minecraft:crafting_shaped".equals(model.serializerId()) || !slotKey.startsWith("input.")) {
            throw new IllegalArgumentException("only shaped crafting input slots can be cleared");
        }
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        fields.put(slotKey + ".item", "minecraft:air");
        fields.put(slotKey + ".count", "0");
        return new RecipePatch(model.recipeId(), model.serializerId(), model.baseFingerprint(), fields);
    }

    static RecipePatch setOutputCount(EditorModel model, int count) {
        if (count < 1 || count > 64) {
            throw new IllegalArgumentException("output count must be between 1 and 64");
        }
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("output.count", Integer.toString(count));
        return new RecipePatch(model.recipeId(), model.serializerId(), model.baseFingerprint(), fields);
    }

    private static Optional<EditorIngredient> simpleIngredient(Ingredient ingredient) {
        if (ingredient == null || ingredient.isEmpty() || ingredient.isCustom()) {
            return Optional.empty();
        }
        java.util.List<ItemStack> items = ingredient.items().map(net.minecraft.core.Holder::value).map(ItemStack::new).toList();
        return items.size() == 1 ? simpleStack(items.get(0)) : Optional.<EditorIngredient>empty();
    }

    private static Optional<EditorIngredient> simpleStack(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getCount() < 1 || stack.getCount() > 64) {
            return Optional.empty();
        }
        if (!stack.isComponentsPatchEmpty()) {
            return Optional.empty();
        }
        Identifier itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return itemId == null
                ? Optional.<EditorIngredient>empty()
                : Optional.of(new EditorIngredient(itemId.toString(), stack.getCount()));
    }

    private static String fingerprint(Identifier recipeId, Identifier serializerId, List<EditorSlot> slots) {
        StringBuilder value = new StringBuilder(recipeId.toString()).append('|').append(serializerId);
        for (EditorSlot slot : slots) {
            value.append('|').append(slot.key()).append('=').append(slot.role());
            if (slot.ingredient() != null) {
                value.append(':').append(slot.ingredient().itemId()).append(':').append(slot.ingredient().count());
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8));
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
