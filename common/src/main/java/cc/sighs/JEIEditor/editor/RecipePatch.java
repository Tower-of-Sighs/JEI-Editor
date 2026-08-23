package cc.sighs.JEIEditor.editor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Structured changes that can later be encoded by a target-specific recipe adapter. */
public final class RecipePatch {
    private final String recipeId;
    private final String serializerId;
    private final String baseFingerprint;
    private final Map<String, String> fields;

    public RecipePatch(String recipeId, String serializerId, String baseFingerprint, Map<String, String> fields) {
        this.recipeId = RecipeEditorValidation.requireResourceId(recipeId, "recipeId");
        this.serializerId = RecipeEditorValidation.requireResourceId(serializerId, "serializerId");
        this.baseFingerprint = requireText(baseFingerprint, "baseFingerprint", 128);
        if (fields == null || fields.size() > 128) {
            throw new IllegalArgumentException("fields must contain at most 128 entries");
        }
        LinkedHashMap<String, String> copy = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String key = requireText(entry.getKey(), "field key", 64);
            String value = requireText(entry.getValue(), "field value", 512);
            copy.put(key, value);
        }
        this.fields = Collections.unmodifiableMap(copy);
    }

    public String recipeId() {
        return recipeId;
    }

    public String serializerId() {
        return serializerId;
    }

    public String baseFingerprint() {
        return baseFingerprint;
    }

    public Map<String, String> fields() {
        return fields;
    }

    private static String requireText(String value, String name, int maxLength) {
        if (value == null || value.length() == 0 || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must contain 1 to " + maxLength + " characters");
        }
        return value;
    }
}
