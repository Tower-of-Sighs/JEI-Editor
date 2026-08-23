package cc.sighs.JEIEditor;

import cc.sighs.JEIEditor.editor.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

final class CookingRecipeEditorAdapter {
    private CookingRecipeEditorAdapter() { }
    static Optional<EditorModel> createModel(ResourceLocation id, Recipe<?> value, RegistryAccess registries) {
        if (!(value instanceof AbstractCookingRecipe)) return Optional.empty();
        AbstractCookingRecipe recipe = (AbstractCookingRecipe) value;
        ResourceLocation serializer = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
        if (serializer == null || !supportsSerializer(serializer.toString()) || recipe.getIngredients().size() != 1) return Optional.empty();
        Optional<EditorIngredient> input = simpleIngredient(recipe.getIngredients().get(0));
        Optional<EditorIngredient> output = simpleStack(recipe.getResultItem(registries));
        if (!input.isPresent() || !output.isPresent() || recipe.getExperience() < 0.0F || recipe.getCookingTime() < 1) return Optional.empty();
        List<EditorSlot> slots = Arrays.asList(new EditorSlot("input.0", "input", input.get()), new EditorSlot("output", "output", output.get()));
        Map<String, String> properties = new LinkedHashMap<String, String>();
        properties.put("experience", Float.toString(recipe.getExperience()));
        properties.put("cooking_time", Integer.toString(recipe.getCookingTime()));
        return Optional.of(new EditorModel(id.toString(), serializer.toString(), fingerprint(id, serializer, slots, properties), slots, properties));
    }
    static RecipePatch replaceInput(EditorModel model, String key, ItemStack stack) {
        Optional<EditorIngredient> ingredient = simpleStack(stack);
        if (!ingredient.isPresent() || !"input.0".equals(key)) throw new IllegalArgumentException("only simple cooking input can be replaced");
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("input.0.item", ingredient.get().itemId()); fields.put("input.0.count", Integer.toString(ingredient.get().count()));
        return new RecipePatch(model.recipeId(), model.serializerId(), model.baseFingerprint(), fields);
    }
    static RecipePatch setOutputCount(EditorModel model, int count) {
        if (count != 1) throw new IllegalArgumentException("cooking output count is fixed at 1 on Minecraft 1.20.1");
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>(); fields.put("output.count", Integer.toString(count));
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
        ItemStack[] items = ingredient.getItems(); return items.length == 1 ? simpleStack(items[0]) : Optional.<EditorIngredient>empty();
    }
    private static Optional<EditorIngredient> simpleStack(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getCount() < 1 || stack.getCount() > 64 || stack.hasTag()) return Optional.empty();
        ResourceLocation item = BuiltInRegistries.ITEM.getKey(stack.getItem()); return item == null ? Optional.<EditorIngredient>empty() : Optional.of(new EditorIngredient(item.toString(), stack.getCount()));
    }
    private static String fingerprint(ResourceLocation id, ResourceLocation serializer, List<EditorSlot> slots, Map<String, String> properties) {
        StringBuilder value = new StringBuilder(id.toString()).append('|').append(serializer);
        for (EditorSlot slot : slots) { value.append('|').append(slot.key()).append('=').append(slot.role()); if (slot.ingredient() != null) value.append(':').append(slot.ingredient().itemId()).append(':').append(slot.ingredient().count()); }
        for (Map.Entry<String, String> property : properties.entrySet()) value.append('|').append(property.getKey()).append('=').append(property.getValue());
        try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)); StringBuilder result = new StringBuilder(digest.length * 2); for (byte current : digest) result.append(String.format("%02x", current & 0xff)); return result.toString(); } catch (Exception exception) { throw new IllegalStateException("SHA-256 is required", exception); }
    }
}
