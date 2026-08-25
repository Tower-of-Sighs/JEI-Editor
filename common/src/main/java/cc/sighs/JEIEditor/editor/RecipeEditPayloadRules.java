package cc.sighs.JEIEditor.editor;

/** Limits shared by every loader's play payload implementation. */
public final class RecipeEditPayloadRules {
    public static final int MAX_FIELDS = 128;
    public static final int MAX_PATCHES = RecipeEditBundle.MAX_PATCHES;
    public static final int MAX_FIELD_KEY_LENGTH = 64;
    /** Large enough for a component-bearing ItemStack while keeping payloads bounded. */
    public static final int MAX_FIELD_VALUE_LENGTH = 8192;

    private RecipeEditPayloadRules() {
    }

    public static void requireFieldCount(int count) {
        if (count < 0 || count > MAX_FIELDS) {
            throw new IllegalArgumentException("invalid patch field count: " + count);
        }
    }

    public static void requirePatchCount(int count) {
        if (count < 0 || count > MAX_PATCHES) {
            throw new IllegalArgumentException("invalid recipe patch count: " + count);
        }
    }

    public static void requireField(String key, String value) {
        requireLength(key, "field key", MAX_FIELD_KEY_LENGTH);
        requireLength(value, "field value", MAX_FIELD_VALUE_LENGTH);
    }

    private static void requireLength(String value, String name, int maxLength) {
        if (value == null || value.length() == 0 || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must contain 1 to " + maxLength + " characters");
        }
    }
}
