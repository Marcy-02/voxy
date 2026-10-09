package me.cortex.voxy.client.compat.create;

import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;

/**
 * Handles reloading of distant Create meshes, bogeys, tracks, contraptions, and kinetics
 * when the block texture atlas or shaders change (e.g., F3+T, resource pack swap, shader toggle).
 */
public final class CreateAtlasReloadListener {
    private static long lastReloadMs = 0;
    private static final long DEBOUNCE_MS = 500;

    private CreateAtlasReloadListener() {}

    public static void onReload() {
        if (ModList.get() == null || !ModList.get().isLoaded("create")) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastReloadMs < DEBOUNCE_MS) {
            return;
        }
        lastReloadMs = now;

        Minecraft.getInstance().execute(() -> {
            var mc = Minecraft.getInstance();
            if (mc.level == null) {
                return;
            }
            Logger.info("Reloading Create distant LOD meshes and textures...");
            try {
                // 1. Clear cached bogey meshes (frees GPU buffers so they re-capture with current atlas)
                DistantBogeyMeshes.clearAll();

                // 2. Rebake all resident train carriage shapes with the updated texture atlas
                DistantTrainManager.rebakeAll();

                // 3. Clear and re-queue track meshes
                if (DistantTrackRenderer.INSTANCE != null) {
                    DistantTrackRenderer.INSTANCE.onAtlasReload();
                }

                // 4. Drop contraption meshes so they rebuild with new atlas
                DistantContraptionManager.onAtlasReload();

                // 5. Clear kinetic snapshots to re-sweep fresh models
                KineticSnapshots.clearAll();
            } catch (Throwable t) {
                Logger.error("Failed during Create distant LOD atlas reload", t);
            }
        });
    }
}
