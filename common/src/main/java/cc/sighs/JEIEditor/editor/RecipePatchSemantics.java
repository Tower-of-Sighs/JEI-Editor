package cc.sighs.JEIEditor.editor;

import java.util.LinkedHashMap;

/** Recipe-level patch operations that are independent from a loader. */
public final class RecipePatchSemantics {
    public static final String DELETED_FIELD = "recipe.deleted";
    public static final String DELETED_VALUE = "true";
    public static final String CREATED_FIELD = "recipe.new";
    public static final String CREATED_VALUE = "true";

    private RecipePatchSemantics() {
    }

    public static RecipePatch delete(EditorModel model) {
        if (model == null) {
            throw new IllegalArgumentException("model cannot be null");
        }
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        fields.put(DELETED_FIELD, DELETED_VALUE);
        return new RecipePatch(model.recipeId(), model.serializerId(), model.baseFingerprint(), fields);
    }

    public static boolean isDeletion(RecipePatch patch) {
        return patch != null && patch.fields().size() == 1
                && DELETED_VALUE.equals(patch.fields().get(DELETED_FIELD));
    }

    public static boolean isCreation(RecipePatch patch) {
        return patch != null && !isDeletion(patch)
                && CREATED_VALUE.equals(patch.fields().get(CREATED_FIELD));
    }
}
