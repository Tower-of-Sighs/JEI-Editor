package cc.sighs.JEIEditor.editor;

import java.util.ArrayList;
import java.util.List;

/** Bounded local undo/redo history for an unsubmitted recipe patch. */
public final class RecipePatchHistory {
    private static final int MAX_ENTRIES = 64;
    private final List<RecipePatch> undo = new ArrayList<RecipePatch>();
    private final List<RecipePatch> redo = new ArrayList<RecipePatch>();

    public void record(RecipePatch previous) {
        undo.add(previous);
        if (undo.size() > MAX_ENTRIES) {
            undo.remove(0);
        }
        redo.clear();
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    public RecipePatch undo(RecipePatch current) {
        if (undo.isEmpty()) {
            return current;
        }
        redo.add(current);
        return undo.remove(undo.size() - 1);
    }

    public RecipePatch redo(RecipePatch current) {
        if (redo.isEmpty()) {
            return current;
        }
        undo.add(current);
        return redo.remove(redo.size() - 1);
    }

    public void clear() {
        undo.clear();
        redo.clear();
    }
}
