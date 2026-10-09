package cc.sighs.JEIEditor.editor;

/**
 * The kinds of ingredient an editor slot can hold.
 *
 * <p>A slot patch addresses one ingredient with a pair of fields, both prefixed
 * by the slot key: {@code <slotKey>.<idField>} carries the namespaced resource
 * id and {@code <slotKey>.<amountField>} the amount. Items keep the historical
 * {@code .item}/{@code .count} names, so every existing patch, fixture and
 * fingerprint keeps working; a fluid or a Mekanism chemical gets its own names
 * because neither an item count range nor an item registry applies to it.
 *
 * <p>The amount range is per kind. An item count is bounded by the stack size
 * (1..64). A fluid or chemical amount is a volume in millibuckets - the shipped
 * recipes use 1, 2, 8, 10, 50, 80, 100, 125, 200, 250, 400, 500 and 1000 - so
 * the item bound cannot be reused: a 1000 mB recipe would be rejected. Both
 * volumes share one documented ceiling, which is generous for the shipped data
 * while keeping the value a bounded integer.
 */
public enum IngredientKind {
    /** A vanilla/NeoForge {@code ItemStack}; fields {@code .item} / {@code .count}. */
    ITEM("item", "item", "count", 1, 64),
    /** A platform fluid stack; fields {@code .fluid} / {@code .fluid_amount}. */
    FLUID("fluid", "fluid", "fluid_amount", 1, 1000000),
    /** A Mekanism chemical stack; fields {@code .chemical} / {@code .chemical_amount}. */
    CHEMICAL("chemical", "chemical", "chemical_amount", 1, 1000000);

    private final String id;
    private final String idField;
    private final String amountField;
    private final int minAmount;
    private final int maxAmount;

    IngredientKind(String id, String idField, String amountField, int minAmount, int maxAmount) {
        this.id = id;
        this.idField = idField;
        this.amountField = amountField;
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
    }

    /** Stable name of this kind, used in fingerprints and diagnostics. */
    public String id() {
        return id;
    }

    /** Patch property holding this kind's resource id. */
    public String idField() {
        return idField;
    }

    /** Patch property holding this kind's amount. */
    public String amountField() {
        return amountField;
    }

    public int minAmount() {
        return minAmount;
    }

    public int maxAmount() {
        return maxAmount;
    }

    /** Whether {@code amount} is inside this kind's range. */
    public boolean acceptsAmount(int amount) {
        return amount >= minAmount && amount <= maxAmount;
    }

    /** The amount, or an {@link IllegalArgumentException} naming this kind's range. */
    public int requireAmount(int amount) {
        if (!acceptsAmount(amount)) {
            throw new IllegalArgumentException(
                    id + " amount must be between " + minAmount + " and " + maxAmount);
        }
        return amount;
    }

    /** The kind owning a slot patch property, or null for an unknown property. */
    public static IngredientKind forProperty(String property) {
        if (property == null) {
            return null;
        }
        for (IngredientKind kind : values()) {
            if (property.equals(kind.idField) || property.equals(kind.amountField)) {
                return kind;
            }
        }
        return null;
    }

    /** The kind with {@link #id()}, or null. */
    public static IngredientKind forId(String id) {
        if (id == null) {
            return null;
        }
        for (IngredientKind kind : values()) {
            if (id.equals(kind.id)) {
                return kind;
            }
        }
        return null;
    }
}
