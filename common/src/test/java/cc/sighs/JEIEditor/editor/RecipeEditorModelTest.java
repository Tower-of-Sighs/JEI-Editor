package cc.sighs.JEIEditor.editor;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RecipeEditorModelTest {
    @Test
    void acceptsNamespacedIdsAndKeepsFieldsImmutable() {
        Map<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("input.0.item", "minecraft:iron_ingot");
        RecipePatch patch = new RecipePatch(
                "minecraft:test",
                "minecraft:crafting_shaped",
                "fingerprint",
                fields
        );

        fields.put("output.count", "2");

        assertEquals(1, patch.fields().size());
        assertThrows(UnsupportedOperationException.class, () -> patch.fields().put("x", "y"));
    }

    @Test
    void rejectsMalformedIdsAndEmptyPatchMetadata() {
        assertThrows(IllegalArgumentException.class, () ->
                new RecipePatch("minecraft:test", "crafting_shaped", "fingerprint", Collections.<String, String>emptyMap()));
        assertThrows(IllegalArgumentException.class, () ->
                new RecipePatch("minecraft:test", "minecraft:crafting_shaped", "", Collections.<String, String>emptyMap()));
        assertThrows(IllegalArgumentException.class, () ->
                new EditorIngredient("iron_ingot", 1));
    }

    @Test
    void patchHistoryRestoresTheUnsubmittedPatchAndInvalidatesRedoAfterANewEdit() {
        RecipePatch first = patch("minecraft:iron_ingot");
        RecipePatch second = patch("minecraft:gold_ingot");
        RecipePatchHistory history = new RecipePatchHistory();

        history.record(null);
        history.record(first);
        assertEquals(first, history.undo(second));
        assertEquals(null, history.undo(first));
        assertEquals(first, history.redo(null));

        history.record(first);
        assertEquals(false, history.canRedo());
    }

    @Test
    void editSessionCancelClearsPendingChangesAndExitsEditMode() {
        RecipeEditSession session = new RecipeEditSession();
        session.toggle();
        session.apply(patch("minecraft:iron_ingot"));

        session.cancel();

        assertEquals(false, session.isEditing());
        assertEquals(null, session.pending());
        session.undo();
        assertEquals(null, session.pending());
    }

    @Test
    void editSessionResetKeepsEditModeAndMergesSameRecipeFields() {
        RecipeEditSession session = new RecipeEditSession();
        session.toggle();
        session.apply(patch("minecraft:iron_ingot"));
        session.apply(patchWithField("output.count", "2"));

        assertEquals(true, session.isEditing());
        assertEquals("minecraft:iron_ingot", session.pending().fields().get("input.0.item"));
        assertEquals("2", session.pending().fields().get("output.count"));

        session.reset();

        assertEquals(true, session.isEditing());
        assertEquals(null, session.pending());
    }

    @Test
    void editSessionRetainsPendingPatchesForDifferentRecipes() {
        RecipeEditSession session = new RecipeEditSession();
        session.apply(patchForRecipe("minecraft:iron_recipe", "minecraft:iron_ingot"));
        session.apply(patchForRecipe("minecraft:gold_recipe", "minecraft:gold_ingot"));

        assertEquals(2, session.pendingPatches().size());
        assertEquals("minecraft:gold_recipe", session.pending().recipeId());
        assertEquals("minecraft:iron_ingot",
                session.pendingPatches().get(0).fields().get("input.0.item"));
    }

    @Test
    void editSessionCanResetOnlyTheCurrentPage() {
        RecipeEditSession session = new RecipeEditSession();
        session.apply(patchForRecipe("minecraft:iron_recipe", "minecraft:iron_ingot"));
        session.apply(patchForRecipe("minecraft:gold_recipe", "minecraft:gold_ingot"));

        session.remove(java.util.Collections.singleton("minecraft:gold_recipe"));

        assertEquals(1, session.pendingPatches().size());
        assertEquals("minecraft:iron_recipe", session.pending().recipeId());
    }

    @Test
    void recipeDeletionReplacesSlotEditsForTheSameRecipe() {
        RecipeEditSession session = new RecipeEditSession();
        session.apply(patch("minecraft:iron_ingot"));
        EditorModel model = new EditorModel("minecraft:test", "minecraft:crafting_shaped", "fingerprint",
                Collections.<EditorSlot>emptyList());

        session.replace(RecipePatchSemantics.delete(model));

        assertEquals(true, RecipePatchSemantics.isDeletion(session.pending()));
        assertEquals(1, session.pending().fields().size());
    }

    @Test
    void recipePatchSemanticsDistinguishesCreationFromDeletion() {
        Map<String, String> fields = new LinkedHashMap<String, String>();
        fields.put(RecipePatchSemantics.CREATED_FIELD, RecipePatchSemantics.CREATED_VALUE);
        fields.put("output.item", "minecraft:stone");
        RecipePatch patch = new RecipePatch("jeieditor:new_test", "minecraft:crafting_shaped",
                "new:jeieditor:new_test", fields);

        assertEquals(true, RecipePatchSemantics.isCreation(patch));
        assertEquals(false, RecipePatchSemantics.isDeletion(patch));
    }

    @Test
    void editorModelKeepsStablePropertiesImmutable() {
        Map<String, String> properties = new LinkedHashMap<String, String>();
        properties.put("cooking_time", "200");
        EditorModel model = new EditorModel("minecraft:test", "minecraft:smelting", "fingerprint",
                Collections.<EditorSlot>emptyList(), properties);

        properties.put("experience", "1.0");

        assertEquals(1, model.properties().size());
        assertThrows(UnsupportedOperationException.class, () -> model.properties().put("x", "y"));
    }

    @Test
    void auditEntryKeepsPreviousAndNextPatchSnapshotsImmutable() {
        Map<String, String> beforeFields = new LinkedHashMap<String, String>();
        beforeFields.put("input.0.item", "minecraft:iron_ore");
        Map<String, String> afterFields = new LinkedHashMap<String, String>();
        afterFields.put("input.0.item", "minecraft:gold_ore");
        RecipePatch before = new RecipePatch("minecraft:test", "minecraft:smelting", "before", beforeFields);
        RecipePatch after = new RecipePatch("minecraft:test", "minecraft:smelting", "before", afterFields);

        RecipeAuditEntry entry = new RecipeAuditEntry("minecraft:test", "operator", 123L, "SAVE", before, after);

        assertEquals("minecraft:iron_ore", entry.previousFields().get("input.0.item"));
        assertEquals("minecraft:gold_ore", entry.nextFields().get("input.0.item"));
        assertThrows(UnsupportedOperationException.class,
                () -> entry.nextFields().put("x", "y"));
    }

    @Test
    void bundleCodecRoundTripsPatchFieldsAndRejectsUnsafeShapes() {
        RecipePatch patch = patch("minecraft:iron_ingot");
        RecipeEditBundle bundle = new RecipeEditBundle(Arrays.asList(patch));

        RecipeEditBundle decoded = RecipeEditBundleCodec.decode(RecipeEditBundleCodec.encode(bundle));

        assertEquals(1, decoded.patches().size());
        assertEquals("minecraft:iron_ingot", decoded.patches().get(0).fields().get("input.0.item"));
        assertThrows(IllegalArgumentException.class, () -> RecipeEditBundleCodec.decode("[]"));
        assertThrows(IllegalArgumentException.class, () -> RecipeEditBundleCodec.decode(
                "{\"format_version\":1,\"extra\":true,\"patches\":[]}"));
        assertThrows(IllegalArgumentException.class, () -> RecipeEditBundleCodec.decode(
                "{\"format_version\":1,\"patches\":[{\"recipe_id\":\"minecraft:test\",\"serializer\":\"minecraft:crafting_shaped\",\"base_fingerprint\":\"x\",\"fields\":{\"input.0.item\":2}}]}"));
    }

    @Test
    void payloadRulesUseTheSameBoundariesForEveryLoader() {
        RecipeEditPayloadRules.requireFieldCount(0);
        RecipeEditPayloadRules.requireFieldCount(RecipeEditPayloadRules.MAX_FIELDS);
        RecipeEditPayloadRules.requireField("k", "v");
        RecipeEditPayloadRules.requireField(
                repeat('k', RecipeEditPayloadRules.MAX_FIELD_KEY_LENGTH),
                repeat('v', RecipeEditPayloadRules.MAX_FIELD_VALUE_LENGTH));

        assertThrows(IllegalArgumentException.class,
                () -> RecipeEditPayloadRules.requireFieldCount(RecipeEditPayloadRules.MAX_FIELDS + 1));
        assertThrows(IllegalArgumentException.class,
                () -> RecipeEditPayloadRules.requireField("", "v"));
        assertThrows(IllegalArgumentException.class,
                () -> RecipeEditPayloadRules.requireField(
                        "k", repeat('v', RecipeEditPayloadRules.MAX_FIELD_VALUE_LENGTH + 1)));
    }

    private static String repeat(char value, int count) {
        char[] values = new char[count];
        Arrays.fill(values, value);
        return new String(values);
    }

    private static RecipePatch patch(String item) {
        Map<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("input.0.item", item);
        return new RecipePatch("minecraft:test", "minecraft:crafting_shaped", "fingerprint", fields);
    }

    private static RecipePatch patchWithField(String key, String value) {
        Map<String, String> fields = new LinkedHashMap<String, String>();
        fields.put(key, value);
        return new RecipePatch("minecraft:test", "minecraft:crafting_shaped", "fingerprint", fields);
    }

    private static RecipePatch patchForRecipe(String recipeId, String item) {
        Map<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("input.0.item", item);
        return new RecipePatch(recipeId, "minecraft:crafting_shaped", "fingerprint", fields);
    }
}
