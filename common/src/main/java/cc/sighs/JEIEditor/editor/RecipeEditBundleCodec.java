package cc.sighs.JEIEditor.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.Map;

/** Strict, platform-neutral JSON codec for recipe edit bundles. */
public final class RecipeEditBundleCodec {
    private RecipeEditBundleCodec() {
    }

    public static String encode(RecipeEditBundle bundle) {
        if (bundle == null) {
            throw new IllegalArgumentException("bundle must not be null");
        }
        JsonObject root = new JsonObject();
        root.addProperty("format_version", RecipeEditBundle.FORMAT_VERSION);
        JsonArray patches = new JsonArray();
        for (RecipePatch patch : bundle.patches()) {
            JsonObject json = new JsonObject();
            json.addProperty("recipe_id", patch.recipeId());
            json.addProperty("serializer", patch.serializerId());
            json.addProperty("base_fingerprint", patch.baseFingerprint());
            JsonObject fields = new JsonObject();
            for (Map.Entry<String, String> field : patch.fields().entrySet()) {
                fields.addProperty(field.getKey(), field.getValue());
            }
            json.add("fields", fields);
            patches.add(json);
        }
        root.add("patches", patches);
        return root.toString();
    }

    public static RecipeEditBundle decode(String content) {
        if (content == null || content.length() == 0) {
            throw new IllegalArgumentException("bundle is empty");
        }
        JsonElement parsed = JsonParser.parseString(content);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("bundle root must be an object");
        }
        JsonObject root = parsed.getAsJsonObject();
        rejectUnknownKeys(root, "format_version", "patches");
        requirePrimitive(root, "format_version");
        if (root.get("format_version").getAsInt() != RecipeEditBundle.FORMAT_VERSION) {
            throw new IllegalArgumentException("unsupported format version");
        }
        JsonElement patchElement = root.get("patches");
        if (patchElement == null || !patchElement.isJsonArray()) {
            throw new IllegalArgumentException("patches must be an array");
        }
        JsonArray patchArray = patchElement.getAsJsonArray();
        if (patchArray.size() > RecipeEditBundle.MAX_PATCHES) {
            throw new IllegalArgumentException("too many patches");
        }
        java.util.List<RecipePatch> patches = new java.util.ArrayList<RecipePatch>(patchArray.size());
        for (JsonElement element : patchArray) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("patch must be an object");
            }
            JsonObject json = element.getAsJsonObject();
            rejectUnknownKeys(json, "recipe_id", "serializer", "base_fingerprint", "fields");
            String recipeId = stringValue(json, "recipe_id");
            String serializer = stringValue(json, "serializer");
            String fingerprint = stringValue(json, "base_fingerprint");
            JsonElement fieldsElement = json.get("fields");
            if (fieldsElement == null || !fieldsElement.isJsonObject()) {
                throw new IllegalArgumentException("patch fields must be an object");
            }
            Map<String, String> fields = new LinkedHashMap<String, String>();
            for (Map.Entry<String, JsonElement> field : fieldsElement.getAsJsonObject().entrySet()) {
                fields.put(field.getKey(), stringValue(field.getValue(), "patch field"));
            }
            patches.add(new RecipePatch(recipeId, serializer, fingerprint, fields));
        }
        return new RecipeEditBundle(patches);
    }

    private static String stringValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing " + key);
        }
        return stringValue(value, key);
    }

    private static String stringValue(JsonElement value, String name) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return value.getAsString();
    }

    private static void requirePrimitive(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("missing or invalid " + key);
        }
    }

    private static void rejectUnknownKeys(JsonObject object, String... allowed) {
        java.util.Set<String> names = new java.util.HashSet<String>(java.util.Arrays.asList(allowed));
        for (String key : object.keySet()) {
            if (!names.contains(key)) {
                throw new IllegalArgumentException("unknown field: " + key);
            }
        }
    }
}
