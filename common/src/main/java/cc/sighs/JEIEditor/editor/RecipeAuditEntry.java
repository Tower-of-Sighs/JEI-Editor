package cc.sighs.JEIEditor.editor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable server-side audit record for a recipe edit operation. */
public final class RecipeAuditEntry {
    private final String recipeId;
    private final String actor;
    private final long timestamp;
    private final String operation;
    private final String previousSerializerId;
    private final String previousFingerprint;
    private final Map<String, String> previousFields;
    private final String nextSerializerId;
    private final String nextFingerprint;
    private final Map<String, String> nextFields;

    public RecipeAuditEntry(String recipeId, String actor, long timestamp, String operation,
                            RecipePatch previous, RecipePatch next) {
        this.recipeId = requireText(recipeId, "recipeId", 256);
        this.actor = requireText(actor, "actor", 128);
        if (timestamp < 0L) throw new IllegalArgumentException("timestamp must not be negative");
        this.timestamp = timestamp;
        this.operation = requireText(operation, "operation", 32);
        this.previousSerializerId = previous == null ? "" : previous.serializerId();
        this.previousFingerprint = previous == null ? "" : previous.baseFingerprint();
        this.previousFields = copyFields(previous == null ? Collections.<String, String>emptyMap() : previous.fields());
        this.nextSerializerId = next == null ? "" : next.serializerId();
        this.nextFingerprint = next == null ? "" : next.baseFingerprint();
        this.nextFields = copyFields(next == null ? Collections.<String, String>emptyMap() : next.fields());
    }

    public String recipeId() { return recipeId; }
    public String actor() { return actor; }
    public long timestamp() { return timestamp; }
    public String operation() { return operation; }
    public String previousSerializerId() { return previousSerializerId; }
    public String previousFingerprint() { return previousFingerprint; }
    public Map<String, String> previousFields() { return previousFields; }
    public String nextSerializerId() { return nextSerializerId; }
    public String nextFingerprint() { return nextFingerprint; }
    public Map<String, String> nextFields() { return nextFields; }

    private static Map<String, String> copyFields(Map<String, String> fields) {
        if (fields.size() > 128) throw new IllegalArgumentException("audit fields must contain at most 128 entries");
        LinkedHashMap<String, String> copy = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            copy.put(requireText(entry.getKey(), "audit field key", 64), requireText(entry.getValue(),
                    "audit field value", RecipeEditPayloadRules.MAX_FIELD_VALUE_LENGTH));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static String requireText(String value, String name, int maxLength) {
        if (value == null || value.length() == 0 || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must contain 1 to " + maxLength + " characters");
        }
        return value;
    }
}
