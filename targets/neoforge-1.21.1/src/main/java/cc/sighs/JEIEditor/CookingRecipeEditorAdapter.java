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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
        if (ingredients.size() != 1 || !ingredients.get(0).isSimple()) return Optional.empty();
        Optional<EditorIngredient> input = simpleIngredient(ingredients.get(0));
        Optional<EditorIngredient> output = simpleStack(recipe.getResultItem(registries));
        if (!input.isPresent() || !output.isPresent() || recipe.getExperience() < 0.0F
                || recipe.getCookingTime() < 1) return Optional.empty();
        List<EditorSlot> slots = new ArrayList<EditorSlot>();
        slots.add(new EditorSlot("input.0", "input", input.get()));
        slots.add(new EditorSlot("output", "output", output.get()));
        Map<String, String> properties = new LinkedHashMap<String, String>();
        properties.put("experience", Float.toString(recipe.getExperience()));
        properties.put("cooking_time", Integer.toString(recipe.getCookingTime()));
        return Optional.of(new EditorModel(holder.id().toString(), serializer.toString(),
                fingerprint(holder.id(), serializer, slots, properties), slots, properties));
    }

    static RecipePatch replaceInput(EditorModel model, String slotKey, ItemStack stack) {
        Optional<EditorIngredient> ingredient = simpleStack(stack);
        if (!ingredient.isPresent() || !"input.0".equals(slotKey)) {
            throw new IllegalArgumentException("only simple cooking input can be replaced");
        }
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("input.0.item", ingredient.get().itemId());
        fields.put("input.0.count", Integer.toString(ingredient.get().count()));
        return new RecipePatch(model.recipeId(), model.serializerId(), model.baseFingerprint(), fields);
    }

    static RecipePatch replaceOutput(EditorModel model, ItemStack stack) {
        Optional<EditorIngredient> ingredient = simpleStack(stack);
        if (!ingredient.isPresent()) {
            throw new IllegalArgumentException("only simple output items can be used");
        }
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("output.item", ingredient.get().itemId());
        fields.put("output.count", Integer.toString(ingredient.get().count()));
        return new RecipePatch(model.recipeId(), model.serializerId(), model.baseFingerprint(), fields);
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

    private static Optional<EditorIngredient> simpleIngredient(Ingredient ingredient) {
        if (ingredient == null || ingredient.isEmpty() || !ingredient.isSimple()) return Optional.empty();
        ItemStack[] items = ingredient.getItems();
        // JEI commonly exposes furnace inputs backed by an item tag, which
        // expands to several stacks. The editor writes a concrete item when
        // that slot is replaced, so use the first valid stack as the model's
        // representative while leaving untouched ingredients intact on save.
        for (ItemStack item : items) {
            Optional<EditorIngredient> value = simpleStack(item);
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    private static Optional<EditorIngredient> simpleStack(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getCount() < 1 || stack.getCount() > 64
                || !stack.isComponentsPatchEmpty()) return Optional.empty();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? Optional.<EditorIngredient>empty() : Optional.of(new EditorIngredient(id.toString(), stack.getCount()));
    }

    private static String fingerprint(ResourceLocation recipeId, ResourceLocation serializer,
                                      List<EditorSlot> slots, Map<String, String> properties) {
        StringBuilder value = new StringBuilder(recipeId.toString()).append('|').append(serializer);
        for (EditorSlot slot : slots) {
            value.append('|').append(slot.key()).append('=').append(slot.role());
            if (slot.ingredient() != null) value.append(':').append(slot.ingredient().itemId()).append(':').append(slot.ingredient().count());
        }
        for (Map.Entry<String, String> property : properties.entrySet()) {
            value.append('|').append(property.getKey()).append('=').append(property.getValue());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte current : digest) result.append(String.format("%02x", current & 0xff));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
