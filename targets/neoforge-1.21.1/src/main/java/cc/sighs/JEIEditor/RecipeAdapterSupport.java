package cc.sighs.JEIEditor;

import cc.sighs.JEIEditor.editor.EditorIngredient;
import cc.sighs.JEIEditor.editor.EditorSlot;
import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.RecipePatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Shared normalization and identity rules for recipe adapters. */
final class RecipeAdapterSupport {
    private RecipeAdapterSupport() {
    }

    static Optional<EditorIngredient> simpleStack(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getCount() < 1 || stack.getCount() > 64
                || !stack.isComponentsPatchEmpty()) {
            return Optional.empty();
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null
                ? Optional.<EditorIngredient>empty()
                : Optional.of(new EditorIngredient(id.toString(), stack.getCount()));
    }

    /** Use JEI/vanilla's first concrete candidate as a display representative. */
    static Optional<EditorIngredient> simpleIngredient(Ingredient ingredient) {
        if (ingredient == null || ingredient.isEmpty()) {
            return Optional.empty();
        }
        for (ItemStack item : ingredient.getItems()) {
            Optional<EditorIngredient> value = simpleStack(item);
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    static boolean isAirIngredient(Ingredient ingredient) {
        if (ingredient == null || ingredient.isEmpty()) {
            return false;
        }
        ItemStack[] items = ingredient.getItems();
        return items.length == 1 && items[0].is(net.minecraft.world.item.Items.AIR);
    }

    static RecipePatch slotPatch(EditorModel model, String slotKey, EditorIngredient ingredient) {
        if (model == null || slotKey == null || slotKey.isEmpty() || ingredient == null) {
            throw new IllegalArgumentException("recipe slot and ingredient are required");
        }
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        fields.put(slotKey + ".item", ingredient.itemId());
        fields.put(slotKey + ".count", Integer.toString(ingredient.count()));
        return new RecipePatch(model.recipeId(), model.serializerId(), model.baseFingerprint(), fields);
    }

    static String fingerprint(String recipeId, String serializerId,
                              List<EditorSlot> slots, Map<String, String> properties) {
        StringBuilder value = new StringBuilder(recipeId).append('|').append(serializerId);
        for (EditorSlot slot : slots) {
            value.append('|').append(slot.key()).append('=').append(slot.role());
            if (slot.ingredient() != null) {
                value.append(':').append(slot.ingredient().itemId())
                        .append(':').append(slot.ingredient().count());
            }
        }
        if (properties != null) {
            for (Map.Entry<String, String> property : properties.entrySet()) {
                value.append('|').append(property.getKey()).append('=').append(property.getValue());
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte current : digest) {
                result.append(String.format("%02x", current & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
