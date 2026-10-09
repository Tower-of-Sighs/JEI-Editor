package cc.sighs.JEIEditor.platform.recipe;

import cc.sighs.JEIEditor.editor.IngredientKind;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The slot sequence of Create's basin pages, derived from the recipe exactly the
 * way {@code com.simibubi.create.compat.jei.category.BasinCategory.setRecipe}
 * derives it.
 *
 * <p>Evidence (Create 1.21.1-6.0.10, {@code create__create-1.21.1-6.0.10.jar},
 * disassembled with {@code javap -c}):
 *
 * <ul>
 *   <li>{@code BasinCategory.setRecipe} calls
 *       {@code ItemHelper.condenseIngredients(recipe.getIngredients())} and adds one
 *       INPUT slot per entry of the returned list; it then walks
 *       {@code recipe.getFluidIngredients()} and adds one INPUT slot per entry, so
 *       every item slot precedes every fluid slot. For the OUTPUT side it adds one
 *       slot per entry of {@code getRollableResults()} and then one per entry of
 *       {@code getFluidResults()}, with the same ordering rule.</li>
 *   <li>{@code ItemHelper.condenseIngredients} groups the {@code Ingredient}s in
 *       order into {@code Pair<Ingredient, MutableInt>}: an entry joins the first
 *       group whose {@code Ingredient.getItems()} array has the same length and is
 *       element-wise {@code ItemStack.matches}, bumping that group's counter, and
 *       starts a new group otherwise. The JEI slot shows each of the group's items
 *       with {@code setCount(counter)}, so one slot means "this ingredient, N
 *       times". {@code data/create/recipe/compacting/ice.json} writes nine separate
 *       {@code {"item":"minecraft:snow_block"}} entries and the page draws one
 *       slot, while {@code compacting/andesite_from_flint.json} writes
 *       {@code flint, flint, gravel, <lava>} and the page draws three.</li>
 *   <li>The recipe JSON is the source of that split but not of the layout: a
 *       recipe's {@code ingredients} array may interleave item and fluid entries
 *       ({@code mixing/chocolate.json} is
 *       {@code sugar, cocoa_beans, {"type":"neoforge:tag","amount":250,"tag":"c:milk"}})
 *       and the codec partitions it into {@code ingredients} and
 *       {@code fluidIngredients} while preserving each part's relative order
 *       ({@code ProcessingRecipeParams.lambda$codec$0}), which is why the two
 *       partitions can be listed after each other. The same holds for
 *       {@code results} ({@code lambda$codec$1}: item results then fluid results).</li>
 *   <li>{@code PackingCategory.setRecipe} delegates to {@code BasinCategory} for its
 *       {@code COMPACTING} type - the {@code create:packing} page - and only its
 *       {@code AUTO_SQUARE} type ({@code create:automatic_packing}, a different
 *       serializer, {@code create:basin}) draws one slot per un-merged ingredient,
 *       so this sequence describes exactly the two pages whose serializer is
 *       {@code create:mixing} or {@code create:compacting}.</li>
 * </ul>
 *
 * <p>An ingredients entry is a fluid ingredient when its {@code type} names a
 * registered {@code FluidIngredientType} - Create's own
 * {@code CreateCodecs.SIZED_FLUID_INGREDIENT} is
 * {@code FLAT_SIZED_FLUID_INGREDIENT_WITH_TYPE.withAlternative(FluidIngredientOld.CODEC)},
 * both of which require that {@code type} and dispatch on it before the item codec
 * is ever tried - and an item ingredient otherwise. The item half is then
 * cross-checked against the {@code Ingredient}s the mod really loaded (same count,
 * same {@code getItems()} per entry), which is what makes the classification
 * verifiable instead of guessed: a recipe whose entries do not split that way is
 * refused.
 */
final class CreateBasinSlotSequence implements DerivedSlotSequence {
    static final CreateBasinSlotSequence INSTANCE = new CreateBasinSlotSequence();

    private static final String INGREDIENTS = "ingredients";
    private static final String RESULTS = "results";
    private static final String TYPE = "type";
    private static final String ID = "id";
    private static final String FLUID = "fluid";

    /**
     * The kinds both sides of the sequence can hold. An input band and an output
     * band each have their own kind, and which band a slot ordinal falls into is
     * only decidable from the recipe JSON, so the client accepts either.
     */
    private static final Set<IngredientKind> KINDS = Collections.unmodifiableSet(
            new LinkedHashSet<IngredientKind>(
                    Arrays.asList(IngredientKind.ITEM, IngredientKind.FLUID)));

    private CreateBasinSlotSequence() {
    }

    @Override
    public Set<IngredientKind> inputKinds() {
        return KINDS;
    }

    @Override
    public Set<IngredientKind> outputKinds() {
        return KINDS;
    }

    /**
     * The page draws one input slot per condensed item ingredient plus one per
     * fluid ingredient, and neither band has to be present:
     * {@code compacting/honey.json} is one fluid only, {@code ice.json} one merged
     * item group and no fluid. The client cannot know the two sizes without the
     * recipe, so it only requires a page with at least one input slot - the exact
     * count is resolved against the recipe while the patch is written.
     */
    @Override
    public boolean inputCountFits(int count) {
        return count >= 1;
    }

    /** The same bound for the outputs: the basin page always draws at least one. */
    @Override
    public boolean outputCountFits(int count) {
        return count >= 1;
    }

    @Override
    public Optional<List<DerivedSlot>> inputSlots(JsonObject recipe, Recipe<?> loaded) {
        Optional<JsonArray> entries = array(recipe, INGREDIENTS);
        if (!entries.isPresent()) {
            return Optional.empty();
        }
        List<Integer> itemIndices = new ArrayList<Integer>();
        List<Ingredient> itemIngredients = new ArrayList<Ingredient>();
        List<Integer> fluidIndices = new ArrayList<Integer>();
        for (int index = 0; index < entries.get().size(); index++) {
            JsonElement entry = entries.get().get(index);
            if (isFluidIngredient(entry)) {
                fluidIndices.add(Integer.valueOf(index));
                continue;
            }
            Optional<Ingredient> ingredient = decodeIngredient(entry);
            if (!ingredient.isPresent()) {
                return Optional.empty();
            }
            itemIndices.add(Integer.valueOf(index));
            itemIngredients.add(ingredient.get());
        }
        // The independent half of the derivation: the item entries of the JSON have to
        // be exactly the Ingredient objects the mod built from them, in the same order.
        // A recipe whose fluid entries are not the ones identified above therefore
        // disagrees here and is refused instead of being written with a guessed split.
        if (loaded == null) {
            return Optional.empty();
        }
        NonNullList<Ingredient> loadedIngredients = loaded.getIngredients();
        if (loadedIngredients == null || loadedIngredients.size() != itemIngredients.size()) {
            return Optional.empty();
        }
        for (int index = 0; index < itemIngredients.size(); index++) {
            if (!sameItems(loadedIngredients.get(index).getItems(),
                    itemIngredients.get(index).getItems())) {
                return Optional.empty();
            }
        }
        // ItemHelper.condenseIngredients, re-derived here: a group is identified by the
        // Ingredient.getItems() array of the first entry that started it.
        List<ItemStack[]> groups = new ArrayList<ItemStack[]>();
        List<List<String>> groupPaths = new ArrayList<List<String>>();
        for (int index = 0; index < itemIngredients.size(); index++) {
            ItemStack[] items = itemIngredients.get(index).getItems();
            String path = INGREDIENTS + "." + itemIndices.get(index);
            int group = -1;
            for (int candidate = 0; candidate < groups.size(); candidate++) {
                if (sameItems(groups.get(candidate), items)) {
                    group = candidate;
                    break;
                }
            }
            if (group < 0) {
                groups.add(items);
                groupPaths.add(new ArrayList<String>(Collections.singletonList(path)));
            } else {
                groupPaths.get(group).add(path);
            }
        }
        List<DerivedSlot> slots = new ArrayList<DerivedSlot>();
        for (List<String> paths : groupPaths) {
            slots.add(new DerivedSlot(IngredientKind.ITEM, paths));
        }
        for (Integer index : fluidIndices) {
            slots.add(DerivedSlot.of(IngredientKind.FLUID, INGREDIENTS + "." + index));
        }
        return Optional.of(slots);
    }

    /**
     * The basin page draws one output slot per item result and then one per fluid
     * result. {@code results} is decoded with the same {@code either} the mod uses
     * ({@code either(FluidStack.CODEC, ProcessingOutput.CODEC)}), so an entry whose
     * {@code id}/{@code fluid} names a registered fluid is a fluid result and every
     * other object is an item result - the result side has no ordered pair of
     * partitions to cross-check against, because vanilla's {@code Recipe} exposes
     * neither list, so this mirrors the codec instead of verifying it.
     */
    @Override
    public Optional<List<DerivedSlot>> outputSlots(JsonObject recipe, Recipe<?> loaded) {
        Optional<JsonArray> entries = array(recipe, RESULTS);
        if (!entries.isPresent()) {
            return Optional.empty();
        }
        List<DerivedSlot> items = new ArrayList<DerivedSlot>();
        List<DerivedSlot> fluids = new ArrayList<DerivedSlot>();
        for (int index = 0; index < entries.get().size(); index++) {
            JsonElement entry = entries.get().get(index);
            String path = RESULTS + "." + index;
            if (isFluidResult(entry)) {
                fluids.add(DerivedSlot.of(IngredientKind.FLUID, path));
            } else if (entry.isJsonObject() && entry.getAsJsonObject().has(ID)) {
                items.add(DerivedSlot.of(IngredientKind.ITEM, path));
            } else {
                return Optional.empty();
            }
        }
        items.addAll(fluids);
        return Optional.of(items);
    }

    /** The array at {@code key}, or empty when the recipe does not carry one. */
    private static Optional<JsonArray> array(JsonObject recipe, String key) {
        if (recipe == null) {
            return Optional.empty();
        }
        JsonElement node = recipe.get(key);
        return node != null && node.isJsonArray() ? Optional.of(node.getAsJsonArray()) : Optional.empty();
    }

    /**
     * Whether a recipe JSON entry is a fluid ingredient: its {@code type} names a
     * registered fluid ingredient type. Create's {@code SIZED_FLUID_INGREDIENT}
     * requires that {@code type} on both of its branches, so no item ingredient can
     * carry one, and {@code neoforge:block_tag} - an item ingredient type - is
     * registered for items only.
     */
    private static boolean isFluidIngredient(JsonElement entry) {
        if (entry == null || !entry.isJsonObject()) {
            return false;
        }
        JsonElement type = entry.getAsJsonObject().get(TYPE);
        if (type == null || !type.isJsonPrimitive() || !type.getAsJsonPrimitive().isString()) {
            return false;
        }
        ResourceLocation id = ResourceLocation.tryParse(type.getAsString());
        return id != null && NeoForgeRegistries.FLUID_INGREDIENT_TYPES.containsKey(id);
    }

    /** Whether a {@code results} entry decodes as a fluid stack, as the mod's codec decides. */
    private static boolean isFluidResult(JsonElement entry) {
        if (entry == null || !entry.isJsonObject()) {
            return false;
        }
        JsonObject object = entry.getAsJsonObject();
        String id = primitive(object, ID);
        if (id == null) {
            id = primitive(object, FLUID);
        }
        ResourceLocation parsed = id == null ? null : ResourceLocation.tryParse(id);
        return parsed != null && BuiltInRegistries.FLUID.containsKey(parsed);
    }

    private static Optional<Ingredient> decodeIngredient(JsonElement entry) {
        try {
            return Ingredient.CODEC.parse(JsonOps.INSTANCE, entry).result();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    /**
     * {@code ItemStack.matches} element-wise over two item arrays of equal length,
     * which is the comparison {@code ItemHelper.condenseIngredients} groups by.
     */
    private static boolean sameItems(ItemStack[] first, ItemStack[] second) {
        if (first == null || second == null || first.length != second.length) {
            return false;
        }
        for (int index = 0; index < first.length; index++) {
            if (!ItemStack.matches(first[index], second[index])) {
                return false;
            }
        }
        return true;
    }

    private static String primitive(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }
}
