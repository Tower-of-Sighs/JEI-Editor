package cc.sighs.JEIEditor.platform.recipe;

import cc.sighs.JEIEditor.editor.EditorIngredient;
import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.EditorSlot;
import cc.sighs.JEIEditor.editor.RecipePatch;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleItemRecipe;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Adapters for vanilla recipe serializers that are neither crafting nor cooking. */
public final class VanillaSpecialRecipeEditorAdapter {
    private VanillaSpecialRecipeEditorAdapter() {
    }

    public static Optional<EditorModel> createModel(RecipeHolder<?> holder,
                                                      HolderLookup.Provider registries) {
        if (holder == null || registries == null) {
            return Optional.empty();
        }
        Recipe<?> recipe = holder.value();
        ResourceLocation serializer = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
        if (serializer == null || !supportsSerializer(serializer.toString())) {
            return Optional.empty();
        }
        List<Ingredient> ingredients = ingredients(recipe);
        if (ingredients.isEmpty()) {
            return Optional.empty();
        }
        List<EditorSlot> slots = new ArrayList<EditorSlot>();
        for (int index = 0; index < ingredients.size(); index++) {
            Ingredient source = ingredients.get(index);
            if (source == null || source.isEmpty() || RecipeAdapterSupport.isAirIngredient(source)) {
                slots.add(new EditorSlot("input." + index, "input", null));
                continue;
            }
            Optional<EditorIngredient> ingredient = RecipeAdapterSupport.simpleIngredient(source);
            if (!ingredient.isPresent()) {
                return Optional.empty();
            }
            slots.add(new EditorSlot("input." + index, "input", ingredient.get()));
        }
        ItemStack result = recipe.getResultItem(registries);
        Optional<EditorIngredient> output = RecipeAdapterSupport.simpleStack(result);
        // Smithing trim has no fixed output; the game derives it from the
        // three inputs. It is still editable through its input slots.
        slots.add(new EditorSlot("output", "output", output.orElse(null)));
        String recipeId = holder.id().toString();
        String serializerId = serializer.toString();
        return Optional.of(new EditorModel(recipeId, serializerId,
                RecipeAdapterSupport.fingerprint(recipeId, serializerId, slots,
                        java.util.Collections.<String, String>emptyMap()), slots));
    }

    public static RecipePatch replaceInput(EditorModel model, String slotKey, ItemStack stack) {
        Optional<EditorIngredient> ingredient = RecipeAdapterSupport.simpleStack(stack);
        if (!ingredient.isPresent() || slotKey == null || !slotKey.startsWith("input.")) {
            throw new IllegalArgumentException("only simple vanilla input slots can be replaced");
        }
        return RecipeAdapterSupport.slotPatch(model, slotKey, ingredient.get());
    }

    public static RecipePatch replaceOutput(EditorModel model, ItemStack stack) {
        Optional<EditorIngredient> ingredient = RecipeAdapterSupport.simpleStack(stack);
        if (!ingredient.isPresent() || !hasOutput(model)) {
            throw new IllegalArgumentException("this vanilla recipe has no editable output");
        }
        return RecipeAdapterSupport.slotPatch(model, "output", ingredient.get());
    }

    public static RecipePatch clearSlot(EditorModel model, String slotKey) {
        if (!supportsSerializer(model == null ? null : model.serializerId())
                || slotKey == null || !slotKey.startsWith("input.")) {
            throw new IllegalArgumentException("only vanilla input slots can be cleared");
        }
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        fields.put(slotKey + ".item", "minecraft:air");
        fields.put(slotKey + ".count", "0");
        return new RecipePatch(model.recipeId(), model.serializerId(), model.baseFingerprint(), fields);
    }

    public static RecipePatch setOutputCount(EditorModel model, int count) {
        if (!hasOutput(model) || count < 1 || count > 64) {
            throw new IllegalArgumentException("output count must be between 1 and 64");
        }
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("output.count", Integer.toString(count));
        return new RecipePatch(model.recipeId(), model.serializerId(), model.baseFingerprint(), fields);
    }

    public static boolean supportsSerializer(String serializerId) {
        return "minecraft:stonecutting".equals(serializerId)
                || "minecraft:smithing_transform".equals(serializerId)
                || "minecraft:smithing_trim".equals(serializerId);
    }

    /** Returns the ordered vanilla ingredient definitions for JSON round-tripping. */
    public static List<Ingredient> ingredientDefinitions(Recipe<?> recipe) {
        return ingredients(recipe);
    }

    private static boolean hasOutput(EditorModel model) {
        if (model == null) {
            return false;
        }
        for (EditorSlot slot : model.slots()) {
            if ("output".equals(slot.role()) && slot.ingredient() != null) {
                return true;
            }
        }
        return false;
    }

    private static List<Ingredient> ingredients(Recipe<?> recipe) {
        if (recipe instanceof SingleItemRecipe) {
            return new ArrayList<Ingredient>(((SingleItemRecipe) recipe).getIngredients());
        }
        if (!(recipe instanceof SmithingRecipe)) {
            return new ArrayList<Ingredient>();
        }
        List<Ingredient> result = new ArrayList<Ingredient>();
        // SmithingRecipe intentionally exposes only matching predicates. The
        // concrete vanilla implementations keep their three ingredients as
        // private fields, so read those fields in declaration order. This is
        // stable across the vanilla transform and trim serializers and avoids
        // hard-coding either implementation class.
        for (Field field : recipe.getClass().getDeclaredFields()) {
            if (!Ingredient.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(recipe);
                if (value instanceof Ingredient) {
                    result.add((Ingredient) value);
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return new ArrayList<Ingredient>();
            }
        }
        return result.size() == 3 ? result : new ArrayList<Ingredient>();
    }
}
