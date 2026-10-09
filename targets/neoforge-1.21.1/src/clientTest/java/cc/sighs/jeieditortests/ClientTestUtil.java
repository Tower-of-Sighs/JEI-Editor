package cc.sighs.jeieditortests;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Client-side plumbing for the development client tests: hopping onto the
 * render thread, polling until a condition holds and writing the report file.
 *
 * <p>This mirrors the helpers JEI ships in its own {@code clientGameTest}
 * source set. The tests only assert behaviour the real client code exposes, so
 * they never touch JEI or the editor through test-only seams.
 */
public final class ClientTestUtil {
    private static final long CLIENT_TASK_TIMEOUT_SECONDS = 10L;
    private static final long POLL_MILLIS = 50L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ClientTestUtil() {
    }

    /**
     * Runs {@code function} on the client render thread and returns its result.
     * Minecraft work - creating recipe layouts in particular - is only valid
     * there, so a caller on another thread is handed back to the client thread
     * and a runaway task is cut off after ten seconds.
     */
    public static <T> T computeOnClient(Function<Minecraft, T> function) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread()) {
            return function.apply(minecraft);
        }
        CompletableFuture<T> future = new CompletableFuture<T>();
        minecraft.execute(() -> {
            try {
                future.complete(function.apply(minecraft));
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        try {
            return future.get(CLIENT_TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the client thread", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new IllegalStateException("the client task failed: " + cause, cause);
        } catch (TimeoutException exception) {
            throw new IllegalStateException("the client task did not return within "
                    + CLIENT_TASK_TIMEOUT_SECONDS + " seconds", exception);
        }
    }

    /** Polls {@code condition} every 50 ms until it holds, failing after the timeout. */
    public static void waitUntil(BooleanSupplier condition, Duration timeout, Supplier<String> description) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for " + description.get(), exception);
            }
        }
        throw new IllegalStateException("timed out waiting for " + description.get());
    }

    /** Writes one small text file, creating its directory if needed. */
    public static void writeText(Path path, String content) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    public static void writeJson(Path path, JsonObject json) throws IOException {
        writeText(path, GSON.toJson(json));
    }
}
