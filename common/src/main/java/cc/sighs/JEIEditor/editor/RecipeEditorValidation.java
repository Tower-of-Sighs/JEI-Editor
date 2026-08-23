package cc.sighs.JEIEditor.editor;

/** Validation shared by every loader before a patch reaches a platform adapter. */
public final class RecipeEditorValidation {
    private RecipeEditorValidation() {
    }

    public static String requireResourceId(String value, String name) {
        if (value == null || value.length() == 0 || value.length() > 256) {
            throw new IllegalArgumentException(name + " must contain 1 to 256 characters");
        }
        int separator = value.indexOf(':');
        if (separator <= 0 || separator == value.length() - 1 || value.indexOf(':', separator + 1) >= 0) {
            throw new IllegalArgumentException(name + " must be a namespaced resource id");
        }
        return value;
    }
}
