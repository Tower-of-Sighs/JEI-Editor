package cc.sighs.JEIEditor.editor;

import java.util.Objects;

/** A semantic recipe slot. Its key is stable across JEI layout changes. */
public final class EditorSlot {
    private final String key;
    private final String role;
    private final EditorIngredient ingredient;

    public EditorSlot(String key, String role, EditorIngredient ingredient) {
        if (key == null || key.length() == 0 || key.length() > 64) {
            throw new IllegalArgumentException("slot key must contain 1 to 64 characters");
        }
        if (role == null || role.length() == 0 || role.length() > 32) {
            throw new IllegalArgumentException("slot role must contain 1 to 32 characters");
        }
        this.key = key;
        this.role = role;
        this.ingredient = ingredient;
    }

    public String key() {
        return key;
    }

    public String role() {
        return role;
    }

    public EditorIngredient ingredient() {
        return ingredient;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof EditorSlot)) {
            return false;
        }
        EditorSlot that = (EditorSlot) other;
        return key.equals(that.key) && role.equals(that.role) && Objects.equals(ingredient, that.ingredient);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, role, ingredient);
    }
}
