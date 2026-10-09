package cc.sighs.JEIEditor.platform.recipe;

import cc.sighs.JEIEditor.editor.IngredientKind;
import com.google.gson.JsonObject;
import net.minecraft.world.item.crafting.Recipe;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A page whose whole slot sequence a mod's JEI category synthesises from the
 * recipe at display time, so that neither a positional field list nor an ordered
 * segment list can name its slots.
 *
 * <p>The example is Create's basin pages ({@code create:mixing},
 * {@code create:packing},
 * {@link CreateBasinSlotSequence}): {@code BasinCategory.setRecipe} first merges
 * the recipe's item ingredients with equal {@code Ingredient.getItems()} into one
 * slot each, then puts every merged item slot before every fluid ingredient slot.
 * Neither the merge nor the reordering exists in the recipe JSON, and which array
 * entries a merged slot covers depends on the recipe, so a field pattern such as
 * {@code ingredients.%d} addresses a different slot than the page draws.
 *
 * <p>The two halves of the contract are deliberately split the way the two sides
 * can answer them:
 *
 * <ul>
 *   <li>The <b>client</b> has the slot views of the displayed page but no recipe
 *       JSON, so it only checks the count the sequence itself can promise
 *       ({@link #inputCountFits}, {@link #outputCountFits}) and takes the kinds a
 *       slot may hold from {@link #inputKinds()} / {@link #outputKinds()} - the
 *       ingredient the page really shows there decides which band a slot belongs
 *       to, exactly as for a concatenated output list. The value written to such
 *       a slot is a patch for the slot's own kind, so the client emits the same
 *       field shape it emits for every other declared page.</li>
 *   <li>The <b>server</b> has the recipe JSON and the recipe the mod loaded, so it
 *       resolves the whole sequence with {@link #inputSlots} / {@link #outputSlots}
 *       and writes the k-th slot of the patch to the paths the sequence resolved
 *       for ordinal k. A sequence that cannot reproduce the recipe refuses
 *       everything: it answers empty and the patch is not written at all, rather
 *       than writing a guessed field.</li>
 * </ul>
 *
 * <p>Resolution is a re-implementation of what the mod's own category does, not a
 * second opinion about it, so a resolver must cross-check itself against the
 * objects the mod loaded where it can (see
 * {@link CreateBasinSlotSequence#inputSlots}) and answer empty when the recipe
 * does not fit the derivation it knows.
 */
public interface DerivedSlotSequence {
    /**
     * The kinds an input slot of the page may hold. The client accepts any of
     * them for every input slot, because which band an ordinal falls into is only
     * decidable from the recipe JSON.
     */
    Set<IngredientKind> inputKinds();

    /** The kinds an output slot of the page may hold, with the same rule. */
    Set<IngredientKind> outputKinds();

    /**
     * Whether a page with {@code count} input slots can be this sequence at all,
     * judged without the recipe. The exact count is resolved server side; a page
     * whose real count the sequence does not reach refuses the patch there.
     */
    boolean inputCountFits(int count);

    /** The same bound for the output slots. */
    boolean outputCountFits(int count);

    /**
     * The page's input slots, in display order, or empty when this recipe does
     * not fit the derivation - which refuses the whole patch.
     *
     * @param recipe the original recipe JSON of the recipe being patched
     * @param loaded the recipe the mod loaded, or null when there is none; a
     *               resolver that cross-checks against it refuses without it
     */
    Optional<List<DerivedSlot>> inputSlots(JsonObject recipe, Recipe<?> loaded);

    /** The page's output slots, in display order, or empty to refuse the patch. */
    Optional<List<DerivedSlot>> outputSlots(JsonObject recipe, Recipe<?> loaded);
}
