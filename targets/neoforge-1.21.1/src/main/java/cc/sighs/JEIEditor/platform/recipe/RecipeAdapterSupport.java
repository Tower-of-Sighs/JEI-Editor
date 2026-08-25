package cc.sighs.JEIEditor.platform.recipe;

import cc.sighs.JEIEditor.editor.EditorIngredient;
import cc.sighs.JEIEditor.editor.EditorSlot;
import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.RecipePatch;
import cc.sighs.JEIEditor.recipe.RecipeModelSupport;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Shared normalization and identity rules for recipe adapters. */
public final class RecipeAdapterSupport {
    private RecipeAdapterSupport() {
    }

    public static Optional<EditorIngredient> simpleStack(ItemStack stack) {
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
    public static Optional<EditorIngredient> simpleIngredient(Ingredient ingredient) {
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

    public static boolean isAirIngredient(Ingredient ingredient) {
        if (ingredient == null || ingredient.isEmpty()) {
            return false;
        }
        ItemStack[] items = ingredient.getItems();
        return items.length == 1 && items[0].is(net.minecraft.world.item.Items.AIR);
    }

    public static RecipePatch slotPatch(EditorModel model, String slotKey, EditorIngredient ingredient) {
        return RecipeModelSupport.slotPatch(model, slotKey, ingredient);
    }

    public static String fingerprint(String recipeId, String serializerId,
                              List<EditorSlot> slots, Map<String, String> properties) {
        return RecipeModelSupport.fingerprint(recipeId, serializerId, slots, properties);
    }
}
