package cc.sighs.JEIEditor.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure client-side editing state that can be tested without Minecraft or JEI. */
public final class RecipeEditSession {
    private static final int MAX_HISTORY = 64;
    private boolean editing;
    private final LinkedHashMap<String, RecipePatch> pendingPatches =
            new LinkedHashMap<String, RecipePatch>();
    private String activeRecipeId;
    private final List<SessionState> undo = new ArrayList<SessionState>();
    private final List<SessionState> redo = new ArrayList<SessionState>();

    public boolean isEditing() {
        return editing;
    }

    public void toggle() {
        editing = !editing;
    }

    public RecipePatch pending() {
        return activeRecipeId == null ? null : pendingPatches.get(activeRecipeId);
    }

    /** Returns all unsubmitted patches in their insertion order. */
    public List<RecipePatch> pendingPatches() {
        return Collections.unmodifiableList(new ArrayList<RecipePatch>(pendingPatches.values()));
    }

    /** Adds a patch, merging fields for the same recipe while retaining other recipes. */
    public void apply(RecipePatch patch) {
        if (patch == null) {
            throw new IllegalArgumentException("patch cannot be null");
        }
        recordState();
        RecipePatch current = pendingPatches.get(patch.recipeId());
        if (current != null && current.baseFingerprint().equals(patch.baseFingerprint())) {
            Map<String, String> fields = new LinkedHashMap<String, String>(current.fields());
            fields.putAll(patch.fields());
            patch = new RecipePatch(patch.recipeId(), patch.serializerId(), patch.baseFingerprint(), fields);
        }
        pendingPatches.put(patch.recipeId(), patch);
        activeRecipeId = patch.recipeId();
    }

    /** Drops unsubmitted changes while keeping edit mode available. */
    public void reset() {
        pendingPatches.clear();
        activeRecipeId = null;
        undo.clear();
        redo.clear();
    }

    public void undo() {
        if (undo.isEmpty()) {
            return;
        }
        redo.add(snapshot());
        restore(undo.remove(undo.size() - 1));
    }

    public void redo() {
        if (redo.isEmpty()) {
            return;
        }
        undo.add(snapshot());
        restore(redo.remove(redo.size() - 1));
    }

    /** Drops unsubmitted changes and exits edit mode. */
    public void cancel() {
        reset();
        editing = false;
    }

    private void recordState() {
        undo.add(snapshot());
        if (undo.size() > MAX_HISTORY) {
            undo.remove(0);
        }
        redo.clear();
    }

    private SessionState snapshot() {
        return new SessionState(pendingPatches, activeRecipeId);
    }

    private void restore(SessionState state) {
        pendingPatches.clear();
        pendingPatches.putAll(state.patches);
        activeRecipeId = state.activeRecipeId;
    }

    private static final class SessionState {
        private final LinkedHashMap<String, RecipePatch> patches;
        private final String activeRecipeId;

        private SessionState(Map<String, RecipePatch> patches, String activeRecipeId) {
            this.patches = new LinkedHashMap<String, RecipePatch>(patches);
            this.activeRecipeId = activeRecipeId;
        }
    }
}
