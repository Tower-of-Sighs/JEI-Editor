package cc.sighs.JEIEditor.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable batch of recipe patches used by import/export commands. */
public final class RecipeEditBundle {
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_PATCHES = 128;

    private final List<RecipePatch> patches;

    public RecipeEditBundle(List<RecipePatch> patches) {
        if (patches == null || patches.size() > MAX_PATCHES) {
            throw new IllegalArgumentException("patches must contain at most " + MAX_PATCHES + " entries");
        }
        List<RecipePatch> copy = new ArrayList<RecipePatch>(patches.size());
        Set<String> ids = new HashSet<String>();
        for (RecipePatch patch : patches) {
            if (patch == null || !ids.add(patch.recipeId())) {
                throw new IllegalArgumentException("patches must contain unique non-null recipe ids");
            }
            copy.add(patch);
        }
        this.patches = Collections.unmodifiableList(copy);
    }

    public List<RecipePatch> patches() {
        return patches;
    }
}
