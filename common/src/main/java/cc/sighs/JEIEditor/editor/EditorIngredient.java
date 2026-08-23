package cc.sighs.JEIEditor.editor;

import java.util.Objects;

/** A loader-independent ingredient reference used by the editor model. */
public final class EditorIngredient {
    private final String itemId;
    private final int count;

    public EditorIngredient(String itemId, int count) {
        this.itemId = RecipeEditorValidation.requireResourceId(itemId, "itemId");
        if (count < 1 || count > 64) {
            throw new IllegalArgumentException("count must be between 1 and 64");
        }
        this.count = count;
    }

    public String itemId() {
        return itemId;
    }

    public int count() {
        return count;
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
        return count == that.count && itemId.equals(that.itemId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(itemId, count);
    }
}
