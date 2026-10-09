package cc.sighs.JEIEditor.platform.recipe;

import cc.sighs.JEIEditor.editor.EditorIngredient;
import cc.sighs.JEIEditor.editor.EditorSlot;
import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.IngredientKind;
import cc.sighs.JEIEditor.editor.RecipePatch;
import cc.sighs.JEIEditor.recipe.RecipeModelSupport;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.common.Internal;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Shared normalization and identity rules for recipe adapters. */
public final class RecipeAdapterSupport {
    /**
     * The JEI ingredient type uid of a platform fluid stack
     * ({@code mezz.jei.api.neoforge.NeoForgeTypes.FLUID_STACK}).
     */
    public static final String FLUID_TYPE_UID = "fluid_stack";
    /**
     * The JEI ingredient type uid of a Mekanism chemical stack. Mekanism builds its
     * type with JEI's default {@code getUid()} - the ingredient class name - so the
     * uid is the class name of {@code mekanism.api.chemical.ChemicalStack}. The
     * editor targets no Mekanism class directly: this target does not compile
     * against Mekanism, it only reads the uid JEI reports.
     */
    public static final String CHEMICAL_TYPE_UID = "mekanism.api.chemical.ChemicalStack";

    private static final ConcurrentMap<Class<?>, Optional<Method>> AMOUNT_METHODS =
            new ConcurrentHashMap<Class<?>, Optional<Method>>();

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

    /**
     * The kind of a JEI ingredient type uid, or null when the editor cannot
     * address that type at all.
     *
     * <p>Only three kinds are addressable: an item stack, a platform fluid stack
     * and a Mekanism chemical stack. Every other registered type (JEI's own
     * {@code PokemonIngredient}, an energy or an information ingredient) has no
     * recipe JSON field the editor could write, so a drag of one is refused.
     */
    public static IngredientKind kindForTypeUid(String typeUid) {
        if (typeUid == null) {
            return null;
        }
        if (FLUID_TYPE_UID.equals(typeUid)) {
            return IngredientKind.FLUID;
        }
        if (CHEMICAL_TYPE_UID.equals(typeUid)) {
            return IngredientKind.CHEMICAL;
        }
        return null;
    }

    /**
     * The editor ingredient a JEI typed ingredient stands for, or empty when the
     * editor cannot address its type or derive its identity.
     *
     * <p>The identity comes from JEI rather than from the platform class:
     * {@code getItemStack()} only answers for an item ingredient, and for the other
     * kinds the id is the ingredient helper's {@code getResourceLocation} - the
     * registry name of the fluid or chemical, without its components - which is the
     * only form a recipe JSON can name. A chemical's amount comes from the stack
     * itself, because JEI's helper leaves {@code getAmount} at its -1 default for
     * that type.
     */
    public static Optional<EditorIngredient> editorIngredient(ITypedIngredient<?> ingredient) {
        if (ingredient == null) {
            return Optional.empty();
        }
        Optional<ItemStack> stack = ingredient.getItemStack();
        if (stack.isPresent()) {
            return simpleStack(stack.get());
        }
        IngredientKind kind = kindForTypeUid(ingredient.getType().getUid());
        if (kind == null) {
            return Optional.empty();
        }
        Optional<mezz.jei.api.runtime.IJeiRuntime> runtime = Internal.getOptionalJeiRuntime();
        if (!runtime.isPresent()) {
            return Optional.empty();
        }
        return ofKind(kind, ingredient, runtime.get().getIngredientManager());
    }

    private static <T> Optional<EditorIngredient> ofKind(IngredientKind kind, ITypedIngredient<T> ingredient,
                                                         mezz.jei.api.runtime.IIngredientManager manager) {
        T value = ingredient.getIngredient();
        IIngredientHelper<T> helper = manager.getIngredientHelper(ingredient.getType());
        ResourceLocation id;
        long amount;
        try {
            id = helper.getResourceLocation(value);
            amount = kind == IngredientKind.CHEMICAL
                    ? stackAmount(value).orElseGet(() -> helper.getAmount(value))
                    : helper.getAmount(value);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
        if (id == null || amount < kind.minAmount() || amount > kind.maxAmount()) {
            // An amount outside the writable range is refused rather than clamped:
            // a clamped amount would silently rewrite the recipe with a value the
            // user never saw.
            return Optional.empty();
        }
        try {
            return Optional.of(new EditorIngredient(kind, id.toString(), (int) amount));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /**
     * The registered JEI ingredient of {@code typeUid} whose resource id is
     * {@code resourceId}, or empty when that type or ingredient is not registered.
     *
     * <p>This is how the editor shows or drags an ingredient of a mod it does not
     * compile against: the type uid and the id are the only two facts it has, so
     * the concrete stack comes from JEI's own registry.
     */
    public static Optional<ITypedIngredient<?>> typedIngredient(String typeUid, String resourceId) {
        if (typeUid == null || resourceId == null) {
            return Optional.empty();
        }
        Optional<mezz.jei.api.runtime.IJeiRuntime> runtime = Internal.getOptionalJeiRuntime();
        if (!runtime.isPresent()) {
            return Optional.empty();
        }
        mezz.jei.api.runtime.IIngredientManager manager = runtime.get().getIngredientManager();
        Optional<mezz.jei.api.ingredients.IIngredientType<?>> type = manager.getIngredientTypeForUid(typeUid);
        return type.isPresent() ? byResourceId(manager, type.get(), resourceId) : Optional.<ITypedIngredient<?>>empty();
    }

    private static <T> Optional<ITypedIngredient<?>> byResourceId(
            mezz.jei.api.runtime.IIngredientManager manager,
            mezz.jei.api.ingredients.IIngredientType<T> type, String resourceId) {
        IIngredientHelper<T> helper = manager.getIngredientHelper(type);
        for (ITypedIngredient<T> candidate : manager.getAllTypedIngredients(type)) {
            try {
                ResourceLocation id = helper.getResourceLocation(candidate.getIngredient());
                if (id != null && resourceId.equals(id.toString())) {
                    return Optional.of(candidate);
                }
            } catch (RuntimeException ignored) {
                // Skip a candidate whose helper cannot name it; the type itself is optional here.
            }
        }
        return Optional.empty();
    }

    /**
     * The {@code getAmount()} of a stack from a mod the editor does not compile
     * against, read reflectively and cached per class. Absent when the class has no
     * such method, in which case the caller falls back to JEI's helper value.
     */
    private static Optional<Long> stackAmount(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        Optional<Method> method = AMOUNT_METHODS.computeIfAbsent(value.getClass(), type -> {
            try {
                Method found = type.getMethod("getAmount");
                found.setAccessible(true);
                return Optional.of(found);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                return Optional.empty();
            }
        });
        if (!method.isPresent()) {
            return Optional.empty();
        }
        try {
            Object result = method.get().invoke(value);
            return result instanceof Number ? Optional.of(((Number) result).longValue()) : Optional.empty();
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return Optional.empty();
        }
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
