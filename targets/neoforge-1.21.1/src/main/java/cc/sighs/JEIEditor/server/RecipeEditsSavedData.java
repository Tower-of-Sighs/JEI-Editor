package cc.sighs.JEIEditor.server;

import cc.sighs.JEIEditor.editor.RecipePatch;
import cc.sighs.JEIEditor.editor.EditorIngredient;
import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.EditorSlot;
import cc.sighs.JEIEditor.editor.RecipeAuditEntry;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

/** World-scoped patch and audit storage for the generated recipe overrides. */
public final class RecipeEditsSavedData extends SavedData {
    private static final String DATA_ID = "jeieditor_recipe_edits";
    private static final int SCHEMA_VERSION = 2;
    private static final String PATCHES = "patches";
    private static final String BASE_MODELS = "base_models";
    private static final SavedData.Factory<RecipeEditsSavedData> FACTORY =
            new SavedData.Factory<RecipeEditsSavedData>(RecipeEditsSavedData::new, RecipeEditsSavedData::load);

    private final Map<String, RecipePatch> patches = new LinkedHashMap<String, RecipePatch>();
    private final Map<String, EditorModel> baseModels = new LinkedHashMap<String, EditorModel>();
    private final List<RecipeAuditEntry> audits = new ArrayList<RecipeAuditEntry>();

    public static RecipeEditsSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_ID);
    }

    private static RecipeEditsSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        RecipeEditsSavedData data = new RecipeEditsSavedData();
        int schemaVersion = tag.contains("schema_version", Tag.TAG_INT) ? tag.getInt("schema_version") : 0;
        if (schemaVersion > SCHEMA_VERSION) {
            return data;
        }
        ListTag patchList = tag.getList(PATCHES, Tag.TAG_COMPOUND);
        for (int i = 0; i < patchList.size(); i++) {
            CompoundTag patchTag = patchList.getCompound(i);
            ListTag fieldList = patchTag.getList("fields", Tag.TAG_COMPOUND);
            Map<String, String> fields = new LinkedHashMap<String, String>();
            for (int j = 0; j < fieldList.size(); j++) {
                CompoundTag field = fieldList.getCompound(j);
                fields.put(field.getString("key"), field.getString("value"));
            }
            try {
                RecipePatch patch = new RecipePatch(
                        patchTag.getString("recipe_id"),
                        patchTag.getString("serializer"),
                        patchTag.getString("base_fingerprint"),
                        fields);
                data.patches.put(patch.recipeId(), patch);
            } catch (IllegalArgumentException ignored) {
                // Invalid historical data is ignored instead of preventing the world from loading.
            }
        }
        ListTag modelList = tag.getList(BASE_MODELS, Tag.TAG_COMPOUND);
        for (int i = 0; i < modelList.size(); i++) {
            CompoundTag modelTag = modelList.getCompound(i);
            ListTag slotList = modelTag.getList("slots", Tag.TAG_COMPOUND);
            java.util.List<EditorSlot> slots = new java.util.ArrayList<EditorSlot>();
            Map<String, String> properties = new LinkedHashMap<String, String>();
            ListTag propertyList = modelTag.getList("properties", Tag.TAG_COMPOUND);
            for (int j = 0; j < propertyList.size(); j++) {
                CompoundTag property = propertyList.getCompound(j);
                properties.put(property.getString("key"), property.getString("value"));
            }
            for (int j = 0; j < slotList.size(); j++) {
                CompoundTag slotTag = slotList.getCompound(j);
                EditorIngredient ingredient = null;
                if (slotTag.contains("item", Tag.TAG_STRING)) {
                    try {
                        ingredient = new EditorIngredient(slotTag.getString("item"), slotTag.getInt("count"));
                    } catch (IllegalArgumentException ignored) {
                        ingredient = null;
                    }
                }
                slots.add(new EditorSlot(slotTag.getString("key"), slotTag.getString("role"), ingredient));
            }
            try {
                EditorModel model = new EditorModel(modelTag.getString("recipe_id"),
                        modelTag.getString("serializer"), modelTag.getString("base_fingerprint"), slots, properties);
                data.baseModels.put(model.recipeId(), model);
            } catch (IllegalArgumentException ignored) {
                // Invalid historical model data is ignored instead of preventing the world from loading.
            }
        }
        ListTag auditList = tag.getList("audit", Tag.TAG_COMPOUND);
        for (int i = 0; i < auditList.size(); i++) { CompoundTag auditTag = auditList.getCompound(i); try { data.audits.add(new RecipeAuditEntry(auditTag.getString("recipe_id"), auditTag.getString("actor"), auditTag.getLong("timestamp"), auditTag.getString("operation"), readAuditPatch(auditTag, "previous"), readAuditPatch(auditTag, "next"))); } catch (IllegalArgumentException ignored) { } }
        while (data.audits.size() > 128) data.audits.remove(0);
        return data;
    }

    private static RecipePatch readAuditPatch(CompoundTag auditTag, String key) { if (!auditTag.contains(key, Tag.TAG_COMPOUND)) return null; CompoundTag patchTag = auditTag.getCompound(key); ListTag fieldList = patchTag.getList("fields", Tag.TAG_COMPOUND); Map<String, String> fields = new LinkedHashMap<String, String>(); for (int i = 0; i < fieldList.size(); i++) { CompoundTag field = fieldList.getCompound(i); fields.put(field.getString("key"), field.getString("value")); } return new RecipePatch(patchTag.getString("recipe_id"), patchTag.getString("serializer"), patchTag.getString("base_fingerprint"), fields); }

    public RecipePatch put(RecipePatch patch, EditorModel baseModel) {
        RecipePatch previous = patches.put(patch.recipeId(), patch);
        if (baseModel != null) {
            baseModels.putIfAbsent(patch.recipeId(), baseModel);
        }
        setDirty();
        return previous;
    }

    public void restore(String recipeId, RecipePatch previous) {
        if (previous == null) {
            patches.remove(recipeId);
            baseModels.remove(recipeId);
        } else {
            patches.put(recipeId, previous);
        }
        setDirty();
    }

    public RecipePatch remove(String recipeId) {
        RecipePatch previous = patches.remove(recipeId);
        baseModels.remove(recipeId);
        if (previous != null) {
            setDirty();
        }
        return previous;
    }

    public void restore(String recipeId, RecipePatch previous, EditorModel baseModel) {
        if (previous != null && baseModel != null) {
            patches.put(recipeId, previous);
            baseModels.put(recipeId, baseModel);
            setDirty();
        }
    }

    public Map<String, RecipePatch> patches() {
        return Collections.unmodifiableMap(patches);
    }

    public EditorModel baseModel(String recipeId) {
        return baseModels.get(recipeId);
    }

    public void audit(String actor, String operation, RecipePatch previous, RecipePatch next) {
        if (previous == null && next == null) {
            return;
        }
        String recipeId = next != null ? next.recipeId() : previous.recipeId();
        audits.add(new RecipeAuditEntry(recipeId, actor, System.currentTimeMillis(), operation, previous, next));
        while (audits.size() > 128) audits.remove(0);
        setDirty();
    }
    public List<RecipeAuditEntry> auditEntries() { return Collections.unmodifiableList(audits); }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schema_version", SCHEMA_VERSION);
        ListTag patchList = new ListTag();
        for (RecipePatch patch : patches.values()) {
            CompoundTag patchTag = new CompoundTag();
            patchTag.putString("recipe_id", patch.recipeId());
            patchTag.putString("serializer", patch.serializerId());
            patchTag.putString("base_fingerprint", patch.baseFingerprint());

            ListTag fieldList = new ListTag();
            for (Map.Entry<String, String> field : patch.fields().entrySet()) {
                CompoundTag fieldTag = new CompoundTag();
                fieldTag.putString("key", field.getKey());
                fieldTag.putString("value", field.getValue());
                fieldList.add(fieldTag);
            }
            patchTag.put("fields", fieldList);
            patchList.add(patchTag);
        }
        tag.put(PATCHES, patchList);
        ListTag modelList = new ListTag();
        for (EditorModel model : baseModels.values()) {
            CompoundTag modelTag = new CompoundTag();
            modelTag.putString("recipe_id", model.recipeId());
            modelTag.putString("serializer", model.serializerId());
            modelTag.putString("base_fingerprint", model.baseFingerprint());
            ListTag slotList = new ListTag();
            for (EditorSlot slot : model.slots()) {
                CompoundTag slotTag = new CompoundTag();
                slotTag.putString("key", slot.key());
                slotTag.putString("role", slot.role());
                if (slot.ingredient() != null) {
                    slotTag.putString("item", slot.ingredient().itemId());
                    slotTag.putInt("count", slot.ingredient().count());
                }
                slotList.add(slotTag);
            }
            modelTag.put("slots", slotList);
            ListTag propertyList = new ListTag();
            for (Map.Entry<String, String> property : model.properties().entrySet()) {
                CompoundTag propertyTag = new CompoundTag();
                propertyTag.putString("key", property.getKey());
                propertyTag.putString("value", property.getValue());
                propertyList.add(propertyTag);
            }
            modelTag.put("properties", propertyList);
            modelList.add(modelTag);
        }
        tag.put(BASE_MODELS, modelList);
        ListTag auditList = new ListTag();
        for (RecipeAuditEntry audit : audits) { CompoundTag auditTag = new CompoundTag(); auditTag.putString("recipe_id", audit.recipeId()); auditTag.putString("actor", audit.actor()); auditTag.putLong("timestamp", audit.timestamp()); auditTag.putString("operation", audit.operation()); writeAuditPatch(auditTag, "previous", audit, true); writeAuditPatch(auditTag, "next", audit, false); auditList.add(auditTag); }
        tag.put("audit", auditList);
        return tag;
    }

    private static void writeAuditPatch(CompoundTag auditTag, String key, RecipeAuditEntry audit, boolean previous) { String serializer = previous ? audit.previousSerializerId() : audit.nextSerializerId(); if (serializer.isEmpty()) return; CompoundTag patchTag = new CompoundTag(); patchTag.putString("recipe_id", audit.recipeId()); patchTag.putString("serializer", serializer); patchTag.putString("base_fingerprint", previous ? audit.previousFingerprint() : audit.nextFingerprint()); ListTag fields = new ListTag(); for (Map.Entry<String, String> field : (previous ? audit.previousFields() : audit.nextFields()).entrySet()) { CompoundTag fieldTag = new CompoundTag(); fieldTag.putString("key", field.getKey()); fieldTag.putString("value", field.getValue()); fields.add(fieldTag); } patchTag.put("fields", fields); auditTag.put(key, patchTag); }
}
