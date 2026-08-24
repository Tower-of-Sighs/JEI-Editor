package cc.sighs.JEIEditor;

import cc.sighs.JEIEditor.editor.RecipePatch;
import cc.sighs.JEIEditor.editor.RecipeEditBundle;
import cc.sighs.JEIEditor.editor.RecipeEditPayloadRules;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class NeoForge121Network {
    private static final Logger LOGGER = LoggerFactory.getLogger("JEI Editor Network");

    private NeoForge121Network() {
    }

    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(RecipeEditPayload.TYPE, RecipeEditPayload.STREAM_CODEC, NeoForge121Network::handleRecipeEdit)
                .playToServer(RecipeEditDeletePayload.TYPE, RecipeEditDeletePayload.STREAM_CODEC, NeoForge121Network::handleRecipeDelete)
                .playToClient(RecipeEditResultPayload.TYPE, RecipeEditResultPayload.STREAM_CODEC, NeoForge121Network::handleRecipeResult);
    }

    static void send(List<RecipePatch> patches) {
        PacketDistributor.sendToServer(RecipeEditPayload.fromBundle(new RecipeEditBundle(patches)));
    }

    static void sendDelete(String recipeId) {
        PacketDistributor.sendToServer(new RecipeEditDeletePayload(recipeId));
    }

    private static void handleRecipeDelete(RecipeEditDeletePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() == null) {
                context.reply(RecipeEditResultPayload.failure("Permission denied", payload.recipeId));
                return;
            }
            MinecraftServer server = context.player().getServer();
            if (server != null && !RecipeEditorPolicy.load(server).canEdit((net.minecraft.server.level.ServerPlayer) context.player(), payload.recipeId)) {
                context.reply(RecipeEditResultPayload.failure("Permission or namespace policy denied", payload.recipeId));
                return;
            }
            if (server == null || ResourceLocation.tryParse(payload.recipeId) == null) {
                context.reply(RecipeEditResultPayload.failure("Unknown recipe id", payload.recipeId));
                return;
            }
            if (!RecipeEditCoordinator.tryBegin(server)) {
                context.reply(RecipeEditResultPayload.failure("Another recipe reload is in progress", payload.recipeId));
                return;
            }
            RecipePatch previous = RecipeEditsSavedData.get(server).patches().get(payload.recipeId);
            RecipeEditsApplier.reset(server, payload.recipeId).whenComplete((ignored, error) -> server.execute(() -> {
                if (error != null) {
                    LOGGER.error("Failed to remove recipe edit for {}", payload.recipeId, error);
                    RecipeEditCoordinator.finish(server);
                    context.reply(RecipeEditResultPayload.failure(
                            "Recipe reset failed; check server log", payload.recipeId));
                } else {
                    RecipeEditsSavedData data = RecipeEditsSavedData.get(server);
                    data.remove(payload.recipeId);
                    data.audit(context.player().getName().getString(), "RESET", previous, null);
                    RecipeEditCoordinator.finish(server);
                    context.reply(RecipeEditResultPayload.success("Recipe reset to original", payload.recipeId));
                }
            }));
        });
    }

    private static void handleRecipeEdit(RecipeEditPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() == null) {
                LOGGER.warn("Rejected recipe edit from player without permission: {}", context.player());
                context.reply(RecipeEditResultPayload.failure("Permission denied", ""));
                return;
            }
            try {
                List<RecipePatch> patches = payload.toBundle().patches();
                MinecraftServer server = context.player().getServer();
                if (server == null || patches.isEmpty()) {
                    context.reply(RecipeEditResultPayload.failure(
                            server == null ? "Unknown server" : "No recipe edits submitted", ""));
                    return;
                }
                for (RecipePatch patch : patches) {
                    String validationError = validatePatch(server,
                            (net.minecraft.server.level.ServerPlayer) context.player(), patch);
                    if (validationError != null) {
                        context.reply(RecipeEditResultPayload.failure(validationError, patch.recipeId()));
                        return;
                    }
                }
                if (!RecipeEditCoordinator.tryBegin(server)) {
                    context.reply(RecipeEditResultPayload.failure("Another recipe reload is in progress", ""));
                    return;
                }
                LOGGER.info("Received batch recipe edit request with {} patches from {}",
                        patches.size(), context.player().getName().getString());
                applyRecipeBatch(server, context, patches, 0);
            } catch (IllegalArgumentException exception) {
                LOGGER.warn("Rejected invalid recipe edit payload from {}: {}",
                        context.player().getName().getString(), exception.getMessage());
                context.reply(RecipeEditResultPayload.failure(
                        "Invalid recipe edit payload: " + exception.getMessage(), ""));
            }
        });
    }

    private static String validatePatch(MinecraftServer server, net.minecraft.server.level.ServerPlayer player,
                                        RecipePatch patch) {
        ResourceLocation recipeId = ResourceLocation.tryParse(patch.recipeId());
        if (recipeId == null) {
            return "Unknown recipe id";
        }
        if (!RecipeEditorPolicy.load(server).canEdit(player, patch.recipeId())) {
            return "Permission or namespace policy denied";
        }
        RecipeHolder<?> holder = server.getRecipeManager().byKey(recipeId).orElse(null);
        if (holder == null) {
            return "Recipe does not exist";
        }
        ResourceLocation serializerId = net.minecraft.core.registries.BuiltInRegistries.RECIPE_SERIALIZER
                .getKey(holder.value().getSerializer());
        if (serializerId == null || !serializerId.toString().equals(patch.serializerId())) {
            return "Recipe serializer mismatch";
        }
        return RecipeEditsApplier.canApply(server, patch)
                ? null
                : "Recipe patch is stale or unsupported";
    }

    private static void applyRecipeBatch(MinecraftServer server, IPayloadContext context,
                                         List<RecipePatch> patches, int index) {
        if (index >= patches.size()) {
            RecipeEditCoordinator.finish(server);
            context.reply(RecipeEditResultPayload.success(
                    "Saved " + patches.size() + " recipe edits", ""));
            return;
        }
        RecipePatch patch = patches.get(index);
        RecipeHolder<?> beforeHolder = server.getRecipeManager()
                .byKey(ResourceLocation.tryParse(patch.recipeId())).orElse(null);
        cc.sighs.JEIEditor.editor.EditorModel beforeModel = beforeHolder == null ? null
                : RecipeEditorAdapters.createModel(beforeHolder, server.registryAccess()).orElse(null);
        RecipeEditsSavedData data = RecipeEditsSavedData.get(server);
        RecipePatch previous = data.patches().get(patch.recipeId());
        RecipeEditsApplier.apply(server, patch).whenComplete((ignored, error) -> server.execute(() -> {
            if (error != null) {
                LOGGER.error("Failed to apply recipe edit for {}", patch.recipeId(), error);
                RecipeEditCoordinator.finish(server);
                context.reply(RecipeEditResultPayload.failure(
                        "Recipe reload failed; change was not accepted", patch.recipeId()));
                return;
            }
            if (beforeModel != null) {
                data.put(patch, beforeModel);
                data.audit(context.player().getName().getString(), "SAVE", previous, patch);
            }
            LOGGER.info("Applied recipe edit for {} and reloaded server recipes", patch.recipeId());
            applyRecipeBatch(server, context, patches, index + 1);
        }));
    }

    private static void handleRecipeResult(RecipeEditResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientEditorState.applyResult(payload.success, payload.message, payload.recipeId));
    }

    static final class RecipeEditPayload implements CustomPacketPayload {
        static final Type<RecipeEditPayload> TYPE = new Type<RecipeEditPayload>(
                ResourceLocation.fromNamespaceAndPath(JEIEditorNeoForge121.MOD_ID, "edit_recipe"));

        static final StreamCodec<RegistryFriendlyByteBuf, RecipeEditPayload> STREAM_CODEC =
                StreamCodec.of(RecipeEditPayload::write, RecipeEditPayload::read);

        private final List<RecipePatch> patches;

        RecipeEditPayload(RecipeEditBundle bundle) {
            this.patches = new ArrayList<RecipePatch>(bundle.patches());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        RecipeEditBundle toBundle() {
            return new RecipeEditBundle(patches);
        }

        static RecipeEditPayload fromBundle(RecipeEditBundle bundle) {
            return new RecipeEditPayload(bundle);
        }

        private static void write(RegistryFriendlyByteBuf buffer, RecipeEditPayload payload) {
            RecipeEditPayloadRules.requirePatchCount(payload.patches.size());
            buffer.writeVarInt(payload.patches.size());
            for (RecipePatch patch : payload.patches) {
                buffer.writeUtf(patch.recipeId(), 256);
                buffer.writeUtf(patch.serializerId(), 256);
                buffer.writeUtf(patch.baseFingerprint(), 128);
                RecipeEditPayloadRules.requireFieldCount(patch.fields().size());
                buffer.writeVarInt(patch.fields().size());
                for (Map.Entry<String, String> entry : patch.fields().entrySet()) {
                    RecipeEditPayloadRules.requireField(entry.getKey(), entry.getValue());
                    buffer.writeUtf(entry.getKey(), 64);
                    buffer.writeUtf(entry.getValue(), 512);
                }
            }
        }

        private static RecipeEditPayload read(RegistryFriendlyByteBuf buffer) {
            int patchCount = buffer.readVarInt();
            RecipeEditPayloadRules.requirePatchCount(patchCount);
            List<RecipePatch> patches = new ArrayList<RecipePatch>(patchCount);
            for (int patchIndex = 0; patchIndex < patchCount; patchIndex++) {
                String recipeId = buffer.readUtf(256);
                String serializerId = buffer.readUtf(256);
                String baseFingerprint = buffer.readUtf(128);
                int fieldCount = buffer.readVarInt();
                RecipeEditPayloadRules.requireFieldCount(fieldCount);
                Map<String, String> fields = new LinkedHashMap<String, String>();
                for (int fieldIndex = 0; fieldIndex < fieldCount; fieldIndex++) {
                    String key = buffer.readUtf(64);
                    String value = buffer.readUtf(512);
                    RecipeEditPayloadRules.requireField(key, value);
                    fields.put(key, value);
                }
                patches.add(new RecipePatch(recipeId, serializerId, baseFingerprint, fields));
            }
            return new RecipeEditPayload(new RecipeEditBundle(patches));
        }
    }

    static final class RecipeEditDeletePayload implements CustomPacketPayload {
        static final Type<RecipeEditDeletePayload> TYPE = new Type<RecipeEditDeletePayload>(
                ResourceLocation.fromNamespaceAndPath(JEIEditorNeoForge121.MOD_ID, "delete_recipe_edit"));
        static final StreamCodec<RegistryFriendlyByteBuf, RecipeEditDeletePayload> STREAM_CODEC =
                StreamCodec.of(RecipeEditDeletePayload::write, RecipeEditDeletePayload::read);

        private final String recipeId;

        RecipeEditDeletePayload(String recipeId) {
            this.recipeId = recipeId;
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        private static void write(RegistryFriendlyByteBuf buffer, RecipeEditDeletePayload payload) {
            buffer.writeUtf(payload.recipeId, 256);
        }

        private static RecipeEditDeletePayload read(RegistryFriendlyByteBuf buffer) {
            return new RecipeEditDeletePayload(buffer.readUtf(256));
        }
    }

    static final class RecipeEditResultPayload implements CustomPacketPayload {
        static final Type<RecipeEditResultPayload> TYPE = new Type<RecipeEditResultPayload>(
                ResourceLocation.fromNamespaceAndPath(JEIEditorNeoForge121.MOD_ID, "edit_recipe_result"));
        static final StreamCodec<RegistryFriendlyByteBuf, RecipeEditResultPayload> STREAM_CODEC =
                StreamCodec.of(RecipeEditResultPayload::write, RecipeEditResultPayload::read);

        private final boolean success;
        private final String message;
        private final String recipeId;

        private RecipeEditResultPayload(boolean success, String message, String recipeId) {
            this.success = success;
            this.message = message;
            this.recipeId = recipeId;
        }

        static RecipeEditResultPayload success(String message, String recipeId) {
            return new RecipeEditResultPayload(true, message, recipeId);
        }

        static RecipeEditResultPayload failure(String message, String recipeId) {
            return new RecipeEditResultPayload(false, message, recipeId);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        private static void write(RegistryFriendlyByteBuf buffer, RecipeEditResultPayload payload) {
            buffer.writeBoolean(payload.success);
            buffer.writeUtf(payload.message, 256);
            buffer.writeUtf(payload.recipeId, 256);
        }

        private static RecipeEditResultPayload read(RegistryFriendlyByteBuf buffer) {
            return new RecipeEditResultPayload(buffer.readBoolean(), buffer.readUtf(256), buffer.readUtf(256));
        }
    }
}
