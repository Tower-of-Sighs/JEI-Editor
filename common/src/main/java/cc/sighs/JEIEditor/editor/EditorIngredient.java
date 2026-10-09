package cc.sighs.JEIEditor.editor;

import java.util.Objects;

/**
 * A loader-independent ingredient reference used by the editor model.
 *
 * <p>An ingredient is a kind ({@link IngredientKind}) plus a namespaced resource
 * id and an amount. The kind decides both the patch field names
 * ({@code IngredientKind.idField()} / {@code amountField()}) and the amount range:
 * an item count stays 1..64, while a fluid or chemical amount is a volume in
 * millibuckets and would be rejected by that bound.
 *
 * <p>{@link #itemId()} and {@link #count()} are the historical item-only
 * accessors. They are kept because the whole item path - crafting, cooking,
 * fuel, every declared item page - addresses the id and the amount through them;
 * for a non-item kind they return the ingredient's own id and amount.
 */
public final class EditorIngredient {
    private final IngredientKind kind;
    private final String id;
    private final int amount;

    /** An item ingredient, the shape every pre-existing call site builds. */
    public EditorIngredient(String itemId, int count) {
        this(IngredientKind.ITEM, itemId, count);
    }

    /** An ingredient of any kind. */
    public EditorIngredient(IngredientKind kind, String id, int amount) {
        this.kind = kind == null ? IngredientKind.ITEM : kind;
        this.id = RecipeEditorValidation.requireResourceId(id, this.kind.id() + " id");
        this.amount = this.kind.requireAmount(amount);
    }

    public IngredientKind kind() {
        return kind;
    }

    public String id() {
        return id;
    }

    public int amount() {
        return amount;
    }

    /** @return {@link #id()}; the item path's name for it. */
    public String itemId() {
        return id;
    }

    /** @return {@link #amount()}; the item path's name for it. */
    public int count() {
        return amount;
    }

    /** Whether this ingredient is a plain item. */
    public boolean isItem() {
        return kind == IngredientKind.ITEM;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof EditorIngredient)) {
            return false;
        }
        EditorIngredient that = (EditorIngredient) other;
        return kind == that.kind && amount == that.amount && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, id, amount);
    }
}
