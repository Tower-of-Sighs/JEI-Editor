package cc.sighs.JEIEditor.editor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The per-slot fields of a patch, read and written kind-aware.
 *
 * <p>One slot owns one ingredient, addressed by the two fields
 * {@code <slotKey>.<idField>} and {@code <slotKey>.<amountField>} of the
 * ingredient's {@link IngredientKind}. Every reader and writer of a slot patch
 * goes through this class so the field names and the "the whole slot was
 * cleared" rule exist once.
 */
public final class SlotPatchFields {
    /** The item id a cleared item slot carries, exactly as before this class existed. */
    public static final String CLEARED_ITEM = "minecraft:air";
    /** The amount a cleared item slot carries. */
    public static final String CLEARED_AMOUNT = "0";

    private SlotPatchFields() {
    }

    /** The fields that replace one entire slot with {@code ingredient}. */
    public static Map<String, String> replace(String slotKey, EditorIngredient ingredient) {
        if (slotKey == null || slotKey.isEmpty() || ingredient == null) {
            throw new IllegalArgumentException("recipe slot and ingredient are required");
        }
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        fields.put(slotKey + "." + ingredient.kind().idField(), ingredient.id());
        fields.put(slotKey + "." + ingredient.kind().amountField(),
                Integer.toString(ingredient.amount()));
        return fields;
    }

    /** The fields that empty an item slot. Only an item has a documented clear form. */
    public static Map<String, String> clearItem(String slotKey) {
        if (slotKey == null || slotKey.isEmpty()) {
            throw new IllegalArgumentException("recipe slot is required");
        }
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        fields.put(slotKey + "." + IngredientKind.ITEM.idField(), CLEARED_ITEM);
        fields.put(slotKey + "." + IngredientKind.ITEM.amountField(), CLEARED_AMOUNT);
        return fields;
    }

    /** Whether the patch carries any field of {@code slotKey}. */
    public static boolean touches(Map<String, String> fields, String slotKey) {
        if (fields == null || slotKey == null) {
            return false;
        }
        String prefix = slotKey + ".";
        for (String key : fields.keySet()) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a declared slot accepts one property of a patch, which is the whole
     * kind half of the server gate for one field of a declared page: the property
     * has to be the id or the amount field of a kind, that kind has to be the one
     * kind the patch names for the slot, and it has to be one the declaration
     * accepts there.
     *
     * <p>The client emits exactly these shapes ({@link #replace}, {@link #clearItem},
     * {@link IngredientKind#amountField()} only for a count edit), so this is the
     * contract between what the client can send and what the server takes: a shape
     * the client produces for a slot of kind {@code k} is accepted for every
     * declaration that names {@code k} for that slot, and refused for every
     * declaration that does not. The amount and the id are checked by the caller,
     * which has the registries; this method decides the kind alone.
     */
    public static boolean acceptsProperty(Map<String, String> fields, String slotKey,
                                          String property, Set<IngredientKind> declaredKinds) {
        if (declaredKinds == null) {
            return false;
        }
        IngredientKind kind = IngredientKind.forProperty(property);
        return kind != null && kindOf(fields, slotKey) == kind && declaredKinds.contains(kind);
    }

    /**
     * The kind of ingredient {@code slotKey} carries in this patch, or null when
     * the patch says nothing about the slot or names more than one kind for it.
     * A mixed slot is malformed and every caller refuses it; answering null here
     * keeps that refusal in the caller that has the message for it.
     */
    public static IngredientKind kindOf(Map<String, String> fields, String slotKey) {
        IngredientKind found = null;
        for (IngredientKind kind : IngredientKind.values()) {
            if (fields != null && (fields.containsKey(slotKey + "." + kind.idField())
                    || fields.containsKey(slotKey + "." + kind.amountField()))) {
                if (found != null) {
                    return null;
                }
                found = kind;
            }
        }
        return found;
    }

    /** The resource id the patch sets for {@code slotKey}, or null. */
    public static String id(Map<String, String> fields, String slotKey) {
        IngredientKind kind = kindOf(fields, slotKey);
        return kind == null ? null : fields.get(slotKey + "." + kind.idField());
    }

    /** The amount the patch sets for {@code slotKey}, or null. */
    public static String amount(Map<String, String> fields, String slotKey) {
        IngredientKind kind = kindOf(fields, slotKey);
        return kind == null ? null : fields.get(slotKey + "." + kind.amountField());
    }

    /**
     * The ingredient a patch yields for one slot, or null when it clears the
     * slot or the fields cannot be turned into an ingredient.
     *
     * <p>A field the patch does not carry keeps the {@code original} value, which
     * is what makes a count-only (or amount-only) edit work. Returns null when
     * nothing can be assembled, which every writer treats as "this slot has no
     * ingredient to write".
     */
    public static EditorIngredient patched(Map<String, String> fields, String slotKey,
                                           EditorIngredient original) {
        IngredientKind kind = kindOf(fields, slotKey);
        if (kind == null) {
            return original;
        }
        String id = fields.get(slotKey + "." + kind.idField());
        String amount = fields.get(slotKey + "." + kind.amountField());
        if (kind == IngredientKind.ITEM
                && (CLEARED_ITEM.equals(id) || CLEARED_AMOUNT.equals(amount))) {
            return null;
        }
        if (id == null && original == null) {
            return null;
        }
        if (original == null && amount == null) {
            return null;
        }
        String resolvedId = id == null ? original.id() : id;
        int resolvedAmount;
        try {
            resolvedAmount = amount == null ? original.amount() : Integer.parseInt(amount);
        } catch (NumberFormatException exception) {
            return null;
        }
        try {
            return new EditorIngredient(kind, resolvedId, resolvedAmount);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
