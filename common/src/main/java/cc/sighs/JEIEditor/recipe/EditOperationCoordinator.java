package cc.sighs.JEIEditor.recipe;

import java.util.Map;
import java.util.WeakHashMap;

/** Prevents overlapping recipe operations for the same server/session key. */
public final class EditOperationCoordinator<K> {
    private final long minimumIntervalMillis;
    private final Map<K, Long> active = new WeakHashMap<K, Long>();

    public EditOperationCoordinator(long minimumIntervalMillis) {
        if (minimumIntervalMillis < 0L) {
            throw new IllegalArgumentException("minimumIntervalMillis cannot be negative");
        }
        this.minimumIntervalMillis = minimumIntervalMillis;
    }

    public synchronized boolean tryBegin(K key) {
        if (key == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long previous = active.get(key);
        if (previous != null && (previous.longValue() == 0L
                || now - previous.longValue() < minimumIntervalMillis)) {
            return false;
        }
        active.put(key, Long.valueOf(0L));
        return true;
    }

    public synchronized void finish(K key) {
        if (key != null) {
            active.put(key, Long.valueOf(System.currentTimeMillis()));
        }
    }
}
