package cc.sighs.JEIEditor.recipe;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/** Pure permission and namespace rules; platform code supplies the actor check. */
public final class RecipePolicyRules {
    private static final int DEFAULT_PERMISSION_LEVEL = 2;
    private final int minPermissionLevel;
    private final Set<String> allowNamespaces;
    private final Set<String> denyNamespaces;

    private RecipePolicyRules(int minPermissionLevel, Set<String> allowNamespaces,
                              Set<String> denyNamespaces) {
        this.minPermissionLevel = minPermissionLevel;
        this.allowNamespaces = allowNamespaces;
        this.denyNamespaces = denyNamespaces;
    }

    public static RecipePolicyRules fromProperties(Properties properties) {
        if (properties == null) {
            properties = new Properties();
        }
        return new RecipePolicyRules(parsePermission(properties.getProperty("min_permission_level")),
                parseNamespaces(properties.getProperty("allow_namespaces")),
                parseNamespaces(properties.getProperty("deny_namespaces")));
    }

    public int minPermissionLevel() {
        return minPermissionLevel;
    }

    public boolean isAllowed(String recipeId) {
        if (recipeId == null) {
            return false;
        }
        int separator = recipeId.indexOf(':');
        if (separator <= 0 || separator == recipeId.length() - 1
                || recipeId.indexOf(':', separator + 1) >= 0) {
            return false;
        }
        String namespace = recipeId.substring(0, separator).toLowerCase(Locale.ROOT);
        String path = recipeId.substring(separator + 1);
        if (!namespace.matches("[a-z0-9_.-]+") || !path.matches("[a-z0-9/._-]+")) {
            return false;
        }
        if (denyNamespaces.contains(namespace)) {
            return false;
        }
        return allowNamespaces.isEmpty() || allowNamespaces.contains(namespace);
    }

    private static int parsePermission(String value) {
        try {
            return Math.max(0, Math.min(4, Integer.parseInt(value.trim())));
        } catch (Exception ignored) {
            return DEFAULT_PERMISSION_LEVEL;
        }
    }

    private static Set<String> parseNamespaces(String value) {
        Set<String> result = new HashSet<String>();
        if (value != null) {
            for (String token : value.split(",")) {
                String namespace = token.trim().toLowerCase(Locale.ROOT);
                if (namespace.matches("[a-z0-9_.-]+")) {
                    result.add(namespace);
                }
            }
        }
        return Collections.unmodifiableSet(result);
    }
}
