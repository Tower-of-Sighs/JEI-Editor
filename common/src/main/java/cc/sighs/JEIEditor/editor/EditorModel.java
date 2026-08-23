package cc.sighs.JEIEditor.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The recipe view model shared by client adapters and pure logic. */
public final class EditorModel {
    private final String recipeId;
    private final String serializerId;
    private final String baseFingerprint;
    private final List<EditorSlot> slots;
    private final Map<String, String> properties;

    public EditorModel(String recipeId, String serializerId, String baseFingerprint, List<EditorSlot> slots) {
        this(recipeId, serializerId, baseFingerprint, slots, Collections.<String, String>emptyMap());
    }

    public EditorModel(String recipeId, String serializerId, String baseFingerprint, List<EditorSlot> slots,
                       Map<String, String> properties) {
        this.recipeId = RecipeEditorValidation.requireResourceId(recipeId, "recipeId");
        this.serializerId = RecipeEditorValidation.requireResourceId(serializerId, "serializerId");
        this.baseFingerprint = requireText(baseFingerprint, "baseFingerprint", 128);
        if (slots == null || slots.size() > 128) {
            throw new IllegalArgumentException("slots must contain at most 128 entries");
        }
        this.slots = Collections.unmodifiableList(new ArrayList<EditorSlot>(slots));
        if (properties == null || properties.size() > 64) {
            throw new IllegalArgumentException("properties must contain at most 64 entries");
        }
        LinkedHashMap<String, String> propertyCopy = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            propertyCopy.put(requireText(entry.getKey(), "property key", 64),
                    requireText(entry.getValue(), "property value", 512));
        }
        this.properties = Collections.unmodifiableMap(propertyCopy);
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

    public List<EditorSlot> slots() {
        return slots;
    }

    public Map<String, String> properties() {
        return properties;
    }

    private static String requireText(String value, String name, int maxLength) {
        if (value == null || value.length() == 0 || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must contain 1 to " + maxLength + " characters");
        }
        return value;
    }
}
