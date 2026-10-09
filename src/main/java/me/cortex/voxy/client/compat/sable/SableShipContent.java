package me.cortex.voxy.client.compat.sable;

import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;

//A sable sub-level is not a Level of its own: a ship's blocks and entities sit in the main client level
//at plot-grid coordinates (around 2.05e7) and only the render pass transforms them onto the ship.
//inBounds is sable's own test for that region - plain integer arithmetic, no allocation.
public final class SableShipContent {
    private SableShipContent() {}

    //inSubLevel runs for every kinetic visual every frame (the ship exemption inside the distance
    //cull), so the level->container lookup is cached per level instead of re-resolved thousands of
    //times a frame.
    //
    //The weak reference alone does not let a closed world go: the container holds its own strong Level
    //field, so caching the container pins the level the weak reference is there to release. Leaving the
    //world takes the level to null, and that path has to clear the cache - returning early there holds
    //the whole previous level, its chunks and its block entities, until the next world's first call.
    private static java.lang.ref.WeakReference<net.minecraft.client.multiplayer.ClientLevel> cachedLevel = new java.lang.ref.WeakReference<>(null);
    private static ClientSubLevelContainer cachedContainer;

    private static ClientSubLevelContainer container() {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            cachedLevel = new java.lang.ref.WeakReference<>(null);
            cachedContainer = null;
            return null;
        }
        if (cachedLevel.get() != level) {
            cachedLevel = new java.lang.ref.WeakReference<>(level);
            cachedContainer = SubLevelContainer.getContainer(level);
        }
        return cachedContainer;
    }

    public static boolean inSubLevel(int chunkX, int chunkZ) {
        ClientSubLevelContainer container = container();
        return container != null && container.inBounds(chunkX, chunkZ);
    }

    public static boolean hasAnyShip() {
        ClientSubLevelContainer container = container();
        return container != null && !container.getAllSubLevels().isEmpty();
    }

    /**
     * Screen extent of every ship in the level. A pass that exists only to let LOD occlude ship content
     * needs no more than this - and nothing at all when the answer is a skip.
     */
    public static SableScreenBounds.Result shipScreenBounds(double cameraX, double cameraY, double cameraZ,
                                                            org.joml.Matrix4f modelView, org.joml.Matrix4f projection,
                                                            double overhangBlocks) {
        ClientSubLevelContainer container = container();
        if (container == null) {
            return SableScreenBounds.Result.allNear();
        }
        return SableScreenBounds.of(container.getAllSubLevels(), cameraX, cameraY, cameraZ,
                modelView, projection, overhangBlocks);
    }

    //Diagnostics surfaced by /voxy debug ship
    public static volatile long ensureCalls;
    public static volatile long ensureRegistered;

    //Sable registers its per-plot Flywheel render state only when an entity joins the level while its
    //ship is already known (VisualizationEventHandlerMixin -> createRenderInfo). On world load the
    //chunk's entities join before sable's sub-level packets arrive, so the plot is never registered -
    //and sable's ContraptionVisualMixin then silently skips its coordinate transform every frame
    //(getInfo == null), leaving the contraption's block body drawn at plot coordinates ~2e7 blocks out:
    //invisible forever. Re-register lazily from the visual's frame loop; once the sub-level exists this
    //fills the gap in one frame and is a cheap map hit afterwards. The sub-level is resolved SPATIALLY
    //from the plot grid, not via Sable.HELPER.getContaining - containment membership is rebuilt from
    //the same join events and can be just as stale after a world reload.
    public static void ensureFlywheelState(net.minecraft.world.entity.Entity entity) {
        var level = entity.level();
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        var cp = entity.chunkPosition();
        if (!container.inBounds(cp.x, cp.z)) {
            return;
        }
        ensureCalls++;
        int plotX = (cp.x >> container.getLogPlotSize()) - container.getOrigin().x;
        int plotZ = (cp.z >> container.getLogPlotSize()) - container.getOrigin().y;
        if (dev.ryanhcode.sable.neoforge.compatibility.flywheel.FlywheelCompatNeoForge.getInfo(net.minecraft.world.level.ChunkPos.asLong(plotX, plotZ)) != null) {
            return;
        }
        var subLevel = container.getSubLevel(plotX, plotZ);
        if (subLevel != null) {
            dev.ryanhcode.sable.neoforge.compatibility.flywheel.FlywheelCompatNeoForge.createRenderInfo(level, subLevel);
            ensureRegistered++;
        }
    }

    //Diagnostic twin of ensureFlywheelState for /voxy debug ship: is sable's per-plot Flywheel render
    //state present for the plot this entity sits in?
    public static String flywheelStateStatus(net.minecraft.world.entity.Entity entity) {
        SubLevelContainer container = SubLevelContainer.getContainer(entity.level());
        if (container == null) {
            return "no container";
        }
        var cp = entity.chunkPosition();
        if (!container.inBounds(cp.x, cp.z)) {
            return "not in plot bounds";
        }
        int plotX = (cp.x >> container.getLogPlotSize()) - container.getOrigin().x;
        int plotZ = (cp.z >> container.getLogPlotSize()) - container.getOrigin().y;
        return dev.ryanhcode.sable.neoforge.compatibility.flywheel.FlywheelCompatNeoForge.getInfo(net.minecraft.world.level.ChunkPos.asLong(plotX, plotZ)) != null ? "ok" : "NULL";
    }

    public static net.minecraft.world.phys.AABB transformEntityAABB(net.minecraft.world.entity.Entity entity) {
        ClientSubLevelContainer container = container();
        if (container == null) {
            return null;
        }
        var cp = entity.chunkPosition();
        if (!container.inBounds(cp.x, cp.z)) {
            return null;
        }
        int plotX = (cp.x >> container.getLogPlotSize()) - container.getOrigin().x;
        int plotZ = (cp.z >> container.getLogPlotSize()) - container.getOrigin().y;
        var subLevel = container.getSubLevel(plotX, plotZ);
        if (!(subLevel instanceof ClientSubLevel clientSubLevel)) {
            return null;
        }
        var box = entity.getBoundingBox();
        if (box == null) {
            return null;
        }
        var pose = clientSubLevel.renderPose();
        org.joml.Vector3d corner = new org.joml.Vector3d();
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 8; i++) {
            corner.set((i & 1) == 0 ? box.minX : box.maxX, (i & 2) == 0 ? box.minY : box.maxY, (i & 4) == 0 ? box.minZ : box.maxZ);
            pose.transformPosition(corner);
            minX = Math.min(minX, corner.x); maxX = Math.max(maxX, corner.x);
            minY = Math.min(minY, corner.y); maxY = Math.max(maxY, corner.y);
            minZ = Math.min(minZ, corner.z); maxZ = Math.max(maxZ, corner.z);
        }
        return new net.minecraft.world.phys.AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public static net.minecraft.world.phys.Vec3 worldPosition(net.minecraft.world.entity.Entity entity) {
        ClientSubLevelContainer container = container();
        if (container == null) {
            return null;
        }
        var cp = entity.chunkPosition();
        if (!container.inBounds(cp.x, cp.z)) {
            return null;
        }
        int plotX = (cp.x >> container.getLogPlotSize()) - container.getOrigin().x;
        int plotZ = (cp.z >> container.getLogPlotSize()) - container.getOrigin().y;
        var subLevel = container.getSubLevel(plotX, plotZ);
        if (!(subLevel instanceof ClientSubLevel clientSubLevel)) {
            return null;
        }
        org.joml.Vector3d pos = new org.joml.Vector3d(entity.getX(), entity.getY(), entity.getZ());
        clientSubLevel.renderPose().transformPosition(pos);
        return new net.minecraft.world.phys.Vec3(pos.x, pos.y, pos.z);
    }
}
