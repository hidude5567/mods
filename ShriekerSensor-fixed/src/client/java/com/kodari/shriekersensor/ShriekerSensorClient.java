package com.kodari.shriekersensor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SculkShriekerBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockStateRaycastContext;

public final class ShriekerSensorClient implements ClientModInitializer {
    /** How far around the player blocks are scanned (needs to cover shrieker range + sensor range + chains). */
    private static final int SCAN_RADIUS = 24;
    /** Only floor blocks this close to the player are highlighted. */
    private static final int HIGHLIGHT_RADIUS = 3;
    /** Re-scan interval in client ticks. */
    private static final int SCAN_INTERVAL = 10;

    /** A sculk sensor hears a step within 8 blocks, a calibrated one within 16. */
    private static final int SENSOR_RANGE_SQ = 8 * 8;
    private static final int CALIBRATED_RANGE_SQ = 16 * 16;
    /** A shrieker reacts to a sensor trigger within 8 blocks. */
    private static final int SHRIEKER_RANGE_SQ = 8 * 8;

    private static final int COLOR = 0xFF369CFF;
    private static final int SHRIEKER_COLOR = 0xFF33FF55;
    private static final float LINE_WIDTH = 2.0F;

    private record Result(List<BlockPos> floors, List<BlockPos> shriekers) {
        static final Result EMPTY = new Result(List.of(), List.of());
    }

    private static volatile Result highlights = Result.EMPTY;
    private static int ticks;

    private static final class Sensor {
        final BlockPos pos;
        final int rangeSq;
        final List<BlockPos> resonators = new ArrayList<>(2);
        /** Sensors that hear a resonance from this sensor's amethyst blocks. */
        final List<Sensor> chain = new ArrayList<>(2);
        /** Shriekers this sensor sets off directly. */
        final List<BlockPos> direct = new ArrayList<>(2);
        Set<BlockPos> reach;

        Sensor(BlockPos pos, int rangeSq) {
            this.pos = pos;
            this.rangeSq = rangeSq;
        }
    }

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(ShriekerSensorClient::tick);
        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            Result toDraw = highlights;
            if (toDraw.floors().isEmpty() && toDraw.shriekers().isEmpty()) {
                return;
            }
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null || client.player == null
                    || context.matrices() == null || context.consumers() == null) {
                return;
            }
            Vec3d camera = client.gameRenderer.getCamera().getCameraPos();
            var buffer = context.consumers().getBuffer(RenderLayers.LINES);
            for (BlockPos pos : toDraw.floors()) {
                VertexRendering.drawOutline(context.matrices(), buffer, VoxelShapes.fullCube(),
                        pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z,
                        COLOR, LINE_WIDTH);
            }
            for (BlockPos pos : toDraw.shriekers()) {
                VertexRendering.drawOutline(context.matrices(), buffer, VoxelShapes.fullCube(),
                        pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z,
                        SHRIEKER_COLOR, LINE_WIDTH);
            }
        });
    }

    private static void tick(MinecraftClient client) {
        if (client.world == null || client.player == null) {
            highlights = Result.EMPTY;
            ticks = 0;
            return;
        }
        if (++ticks < SCAN_INTERVAL) {
            return;
        }
        ticks = 0;
        try {
            highlights = compute(client.world, client.player.getBlockPos());
        } catch (RuntimeException e) {
            // Never let a scan error crash the client.
            highlights = Result.EMPTY;
        }
    }

    private static Result compute(ClientWorld world, BlockPos center) {
        // ---- 1. Scan the surroundings for shriekers and sensors ----
        List<BlockPos> shriekers = new ArrayList<>();
        Set<BlockPos> shriekerSet = new HashSet<>();
        List<Sensor> sensors = new ArrayList<>();
        Map<BlockPos, Sensor> sensorByPos = new HashMap<>();
        BlockPos.Mutable cursor = new BlockPos.Mutable();

        for (int x = -SCAN_RADIUS; x <= SCAN_RADIUS; x++) {
            for (int y = -SCAN_RADIUS; y <= SCAN_RADIUS; y++) {
                for (int z = -SCAN_RADIUS; z <= SCAN_RADIUS; z++) {
                    cursor.set(center.getX() + x, center.getY() + y, center.getZ() + z);
                    BlockState state = world.getBlockState(cursor);
                    if (state.isAir()) {
                        continue;
                    }
                    if (state.isOf(Blocks.SCULK_SHRIEKER)) {
                        // Only shriekers that can summon a warden are a real threat.
                        if (state.get(SculkShriekerBlock.CAN_SUMMON)) {
                            BlockPos pos = cursor.toImmutable();
                            shriekers.add(pos);
                            shriekerSet.add(pos);
                        }
                    } else if (state.isOf(Blocks.SCULK_SENSOR)) {
                        BlockPos pos = cursor.toImmutable();
                        Sensor sensor = new Sensor(pos, SENSOR_RANGE_SQ);
                        sensors.add(sensor);
                        sensorByPos.put(pos, sensor);
                    } else if (state.isOf(Blocks.CALIBRATED_SCULK_SENSOR)) {
                        BlockPos pos = cursor.toImmutable();
                        Sensor sensor = new Sensor(pos, CALIBRATED_RANGE_SQ);
                        sensors.add(sensor);
                        sensorByPos.put(pos, sensor);
                    }
                }
            }
        }

        if (shriekers.isEmpty()) {
            return Result.EMPTY;
        }

        // ---- 2. Build the trigger graph: player -> sensor -> (resonance) -> sensor -> shrieker ----
        for (Sensor sensor : sensors) {
            for (BlockPos shrieker : shriekers) {
                if (distanceSquared(sensor.pos, shrieker) <= SHRIEKER_RANGE_SQ
                        && !isOccluded(world, sensor.pos, shrieker)) {
                    sensor.direct.add(shrieker);
                }
            }
            for (Direction direction : Direction.values()) {
                BlockPos resonator = sensor.pos.offset(direction);
                if (!world.getBlockState(resonator).isIn(BlockTags.VIBRATION_RESONATORS)) {
                    continue;
                }
                for (Sensor other : sensors) {
                    if (other != sensor && !sensor.chain.contains(other)
                            && distanceSquared(resonator, other.pos) <= other.rangeSq
                            && !isOccluded(world, resonator, other.pos)) {
                        sensor.chain.add(other);
                    }
                }
            }
        }

        // ---- 3. Pick unsafe floor blocks near the player, and the shriekers they would set off ----
        List<BlockPos> floors = new ArrayList<>();
        Set<BlockPos> activated = new LinkedHashSet<>();
        for (int x = -HIGHLIGHT_RADIUS; x <= HIGHLIGHT_RADIUS; x++) {
            for (int y = -HIGHLIGHT_RADIUS; y <= HIGHLIGHT_RADIUS; y++) {
                for (int z = -HIGHLIGHT_RADIUS; z <= HIGHLIGHT_RADIUS; z++) {
                    BlockPos floor = center.add(x, y - 1, z);
                    BlockState floorState = world.getBlockState(floor);
                    if (floorState.getCollisionShape(world, floor).isEmpty()) {
                        continue;
                    }
                    // Wool and carpet dampen footsteps: walking on them makes no vibration.
                    if (floorState.isIn(BlockTags.DAMPENS_VIBRATIONS)) {
                        continue;
                    }
                    Set<BlockPos> hit = new HashSet<>();
                    // Standing on a shrieker, or on a sensor, sets it off directly.
                    if (shriekerSet.contains(floor)) {
                        hit.add(floor);
                    }
                    Sensor underfoot = sensorByPos.get(floor);
                    if (underfoot != null) {
                        hit.addAll(reach(underfoot));
                    }
                    BlockPos feet = floor.up();
                    if (world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()) {
                        for (Sensor sensor : sensors) {
                            if (distanceSquared(feet, sensor.pos) <= sensor.rangeSq
                                    && !isOccluded(world, feet, sensor.pos)) {
                                hit.addAll(reach(sensor));
                            }
                        }
                    }
                    if (!hit.isEmpty()) {
                        floors.add(floor);
                        activated.addAll(hit);
                    }
                }
            }
        }
        return new Result(floors, new ArrayList<>(activated));
    }

    /** Every shrieker that is set off when this sensor triggers, following resonance chains. */
    private static Set<BlockPos> reach(Sensor start) {
        if (start.reach != null) {
            return start.reach;
        }
        Set<BlockPos> shriekers = new HashSet<>();
        Set<Sensor> seen = new HashSet<>();
        ArrayDeque<Sensor> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            Sensor current = queue.removeFirst();
            shriekers.addAll(current.direct);
            for (Sensor next : current.chain) {
                if (seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        start.reach = shriekers;
        return shriekers;
    }

    /**
     * Mirrors vanilla's vibration occlusion test: a vibration is blocked only when rays from the
     * source block centre (nudged in each of the six directions) to the destination block centre
     * all hit a wool block ("occludes_vibration_signals"). So a single gap lets it through.
     */
    private static boolean isOccluded(ClientWorld world, BlockPos from, BlockPos to) {
        Vec3d start = Vec3d.ofCenter(from);
        Vec3d end = Vec3d.ofCenter(to);
        for (Direction direction : Direction.values()) {
            Vec3d nudged = start.offset(direction, 1.0E-5);
            boolean hitWool = world.raycast(new BlockStateRaycastContext(nudged, end,
                    state -> state.isIn(BlockTags.OCCLUDES_VIBRATION_SIGNALS))).getType() == HitResult.Type.BLOCK;
            if (!hitWool) {
                return false;
            }
        }
        return true;
    }

    private static long distanceSquared(BlockPos a, BlockPos b) {
        long x = (long) a.getX() - b.getX();
        long y = (long) a.getY() - b.getY();
        long z = (long) a.getZ() - b.getZ();
        return x * x + y * y + z * z;
    }
}
