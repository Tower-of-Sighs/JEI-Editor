package cc.sighs.JEIEditor;

import cc.sighs.JEIEditor.editor.EditorIngredient;
import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.EditorSlot;
import cc.sighs.JEIEditor.editor.RecipePatch;
import cc.sighs.JEIEditor.editor.RecipeAuditEntry;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RecipeEditsSavedData extends SavedData {
    private static final int SCHEMA_VERSION = 2;
    private final Map<String, RecipePatch> patches = new LinkedHashMap<String, RecipePatch>();
    private final Map<String, EditorModel> baseModels = new LinkedHashMap<String, EditorModel>();
    private final List<RecipeAuditEntry> audits = new ArrayList<RecipeAuditEntry>();

    private static final Codec<RecipePatch> PATCH_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("recipe_id").forGetter(RecipePatch::recipeId),
            Codec.STRING.fieldOf("serializer").forGetter(RecipePatch::serializerId),
            Codec.STRING.fieldOf("base_fingerprint").forGetter(RecipePatch::baseFingerprint),
            Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf("fields").forGetter(RecipePatch::fields)
    ).apply(instance, RecipePatch::new));

    private static final Codec<EditorIngredient> INGREDIENT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("item").forGetter(EditorIngredient::itemId),
            Codec.INT.fieldOf("count").forGetter(EditorIngredient::count)
    ).apply(instance, EditorIngredient::new));

    private static final Codec<EditorSlot> SLOT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("key").forGetter(EditorSlot::key),
            Codec.STRING.fieldOf("role").forGetter(EditorSlot::role),
            INGREDIENT_CODEC.optionalFieldOf("ingredient").forGetter(slot -> java.util.Optional.ofNullable(slot.ingredient()))
    ).apply(instance, (key, role, ingredient) -> new EditorSlot(key, role, ingredient.orElse(null))));

    private static final Codec<EditorModel> MODEL_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("recipe_id").forGetter(EditorModel::recipeId),
            Codec.STRING.fieldOf("serializer").forGetter(EditorModel::serializerId),
            Codec.STRING.fieldOf("base_fingerprint").forGetter(EditorModel::baseFingerprint),
            SLOT_CODEC.listOf().fieldOf("slots").forGetter(EditorModel::slots),
            Codec.unboundedMap(Codec.STRING, Codec.STRING)
                    .optionalFieldOf("properties", Collections.<String, String>emptyMap())
                    .forGetter(EditorModel::properties)
    ).apply(instance, (recipeId, serializer, fingerprint, slots, properties) ->
            new EditorModel(recipeId, serializer, fingerprint, slots, properties)));

    private static final Codec<RecipeAuditEntry> AUDIT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("recipe_id").forGetter(RecipeAuditEntry::recipeId),
            Codec.STRING.fieldOf("actor").forGetter(RecipeAuditEntry::actor),
            Codec.LONG.fieldOf("timestamp").forGetter(RecipeAuditEntry::timestamp),
            Codec.STRING.fieldOf("operation").forGetter(RecipeAuditEntry::operation),
            Codec.STRING.optionalFieldOf("previous_serializer", "").forGetter(RecipeAuditEntry::previousSerializerId),
            Codec.STRING.optionalFieldOf("previous_fingerprint", "").forGetter(RecipeAuditEntry::previousFingerprint),
            Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("previous_fields", Collections.<String, String>emptyMap()).forGetter(RecipeAuditEntry::previousFields),
            Codec.STRING.optionalFieldOf("next_serializer", "").forGetter(RecipeAuditEntry::nextSerializerId),
            Codec.STRING.optionalFieldOf("next_fingerprint", "").forGetter(RecipeAuditEntry::nextFingerprint),
            Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("next_fields", Collections.<String, String>emptyMap()).forGetter(RecipeAuditEntry::nextFields)
    ).apply(instance, (recipeId, actor, timestamp, operation, previousSerializer, previousFingerprint, previousFields, nextSerializer, nextFingerprint, nextFields) ->
            new RecipeAuditEntry(recipeId, actor, timestamp, operation,
                    auditPatch(recipeId, previousSerializer, previousFingerprint, previousFields),
                    auditPatch(recipeId, nextSerializer, nextFingerprint, nextFields))));

    private static final Codec<RecipeEditsSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("schema_version", SCHEMA_VERSION).forGetter(data -> SCHEMA_VERSION),
            PATCH_CODEC.listOf().fieldOf("patches").forGetter(data -> new ArrayList<RecipePatch>(data.patches.values())),
            MODEL_CODEC.listOf().fieldOf("base_models").forGetter(data -> new ArrayList<EditorModel>(data.baseModels.values())),
            AUDIT_CODEC.listOf().optionalFieldOf("audit", Collections.<RecipeAuditEntry>emptyList()).forGetter(data -> new ArrayList<RecipeAuditEntry>(data.audits))
    ).apply(instance, (version, patchList, modelList, auditList) -> fromLists(patchList, modelList, auditList)));

    private static final SavedDataType<RecipeEditsSavedData> TYPE = new SavedDataType<RecipeEditsSavedData>(
            Identifier.fromNamespaceAndPath(JEIEditorNeoForge261.MOD_ID, "recipe_edits"),
            RecipeEditsSavedData::new, CODEC);

    static RecipeEditsSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    private static RecipeEditsSavedData fromLists(List<RecipePatch> patchList, List<EditorModel> modelList, List<RecipeAuditEntry> auditList) {
        RecipeEditsSavedData data = new RecipeEditsSavedData();
        for (RecipePatch patch : patchList) data.patches.put(patch.recipeId(), patch);
        for (EditorModel model : modelList) data.baseModels.put(model.recipeId(), model);
        data.audits.addAll(auditList);
        while (data.audits.size() > 128) data.audits.remove(0);
        return data;
    }

    private static RecipePatch auditPatch(String recipeId, String serializer, String fingerprint, Map<String, String> fields) {
        return serializer.isEmpty() ? null : new RecipePatch(recipeId, serializer, fingerprint, fields);
    }

    RecipePatch put(RecipePatch patch, EditorModel baseModel) {
        RecipePatch previous = patches.put(patch.recipeId(), patch);
        baseModels.putIfAbsent(patch.recipeId(), baseModel);
        setDirty();
        return previous;
    }

    void restore(String recipeId, RecipePatch previous) {
        if (previous == null) { patches.remove(recipeId); baseModels.remove(recipeId); }
        else patches.put(recipeId, previous);
        setDirty();
    }

    RecipePatch remove(String recipeId) {
        RecipePatch previous = patches.remove(recipeId);
        baseModels.remove(recipeId);
        if (previous != null) setDirty();
        return previous;
    }

    void restore(String recipeId, RecipePatch previous, EditorModel baseModel) {
        if (previous != null && baseModel != null) {
            patches.put(recipeId, previous);
            baseModels.put(recipeId, baseModel);
            setDirty();
        }
    }

    Map<String, RecipePatch> patches() { return Collections.unmodifiableMap(patches); }
    EditorModel baseModel(String recipeId) { return baseModels.get(recipeId); }
    void audit(String actor, String operation, RecipePatch previous, RecipePatch next) { String recipeId = next != null ? next.recipeId() : previous.recipeId(); audits.add(new RecipeAuditEntry(recipeId, actor, System.currentTimeMillis(), operation, previous, next)); while (audits.size() > 128) audits.remove(0); setDirty(); }
    List<RecipeAuditEntry> auditEntries() { return Collections.unmodifiableList(audits); }
}
