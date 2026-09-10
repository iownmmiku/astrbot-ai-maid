package com.iownmmiku.maid;

import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.*;

/**
 * 探索系统：螺旋式搜索、寻找村庄、标记地标。
 */
public final class MaidExploration {
    private MaidExploration() {
    }

    /**
     * 螺旋式搜索：从家向外一圈圈扩展。
     * @param progress 存储探索进度（当前半径、当前角度）
     */
    public static String spiralSearch(ServerPlayerEntity maid, Map<String, Integer> progress) {
        // 家的位置（第一次探索时记录）
        int homeX = progress.getOrDefault("home_x", maid.getBlockX());
        int homeZ = progress.getOrDefault("home_z", maid.getBlockZ());
        progress.putIfAbsent("home_x", homeX);
        progress.putIfAbsent("home_z", homeZ);

        // 当前搜索半径和角度
        int radius = progress.getOrDefault("search_radius", 16);
        int angle = progress.getOrDefault("search_angle", 0);

        // 螺旋搜索：每 45 度一个点
        double rad = Math.toRadians(angle);
        int targetX = homeX + (int) (radius * Math.cos(rad));
        int targetZ = homeZ + (int) (radius * Math.sin(rad));

        // 走到目标点
        double dist = Math.hypot(maid.getX() - targetX, maid.getZ() - targetZ);
        if (dist > 3.0) {
            MaidBrain.gotoTo(maid, targetX, targetZ);
            return "探索 (半径 " + radius + " 格，角度 " + angle + "°)";
        }

        // 到达目标点，扫描周围
        scanLandmarks(maid, progress);

        // 移到下一个角度
        angle += 45;
        if (angle >= 360) {
            angle = 0;
            radius += 16;  // 扩大半径
            if (radius > 256) {
                return null;  // 探索完成（半径 256 格）
            }
            progress.put("search_radius", radius);
        }
        progress.put("search_angle", angle);

        return "探索中（半径 " + radius + " 格）";
    }

    /**
     * 寻找村庄。
     */
    public static String findVillage(ServerPlayerEntity maid, Map<String, Integer> progress) {
        // 检查是否已找到村庄
        if (progress.containsKey("village_x")) {
            return null;  // 已找到
        }

        // 扫描附近的村民
        ServerWorld world = maid.getServerWorld();
        Box box = maid.getBoundingBox().expand(32.0);
        List<VillagerEntity> villagers = world.getEntitiesByClass(VillagerEntity.class, box, e -> e.isAlive());

        if (!villagers.isEmpty()) {
            VillagerEntity villager = villagers.get(0);
            BlockPos villagePos = villager.getBlockPos();
            progress.put("village_x", villagePos.getX());
            progress.put("village_y", villagePos.getY());
            progress.put("village_z", villagePos.getZ());
            
            AiMaidMod.LOGGER.info("[AI-Maid] {} found village at ({}, {}, {})", 
                maid.getGameProfile().getName(), villagePos.getX(), villagePos.getY(), villagePos.getZ());
            
            return null;  // 找到村庄，任务完成
        }

        // 没找到 → 继续螺旋搜索
        return spiralSearch(maid, progress);
    }

    /**
     * 扫描当前位置周围的地标（村庄、神殿、矿洞入口等）。
     */
    private static void scanLandmarks(ServerPlayerEntity maid, Map<String, Integer> progress) {
        ServerWorld world = maid.getServerWorld();
        BlockPos pos = maid.getBlockPos();

        // 1. 检查村民（村庄）
        Box box = new Box(pos).expand(16.0);
        List<VillagerEntity> villagers = world.getEntitiesByClass(VillagerEntity.class, box, e -> e.isAlive());
        if (!villagers.isEmpty() && !progress.containsKey("village_x")) {
            BlockPos villagePos = villagers.get(0).getBlockPos();
            progress.put("village_x", villagePos.getX());
            progress.put("village_y", villagePos.getY());
            progress.put("village_z", villagePos.getZ());
            AiMaidMod.LOGGER.info("[AI-Maid] {} discovered village at {}", 
                maid.getGameProfile().getName(), villagePos);
        }

        // 2. 检查洞穴入口（简化：找露天的深坑）
        // 扫描附近是否有 Y < -20 的空气方块暴露在地表
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                BlockPos surface = world.getTopPosition(net.minecraft.world.Heightmap.Type.WORLD_SURFACE, pos.add(dx, 0, dz));
                if (surface.getY() < 50 && world.getBlockState(surface.down(10)).isAir()) {
                    // 可能是矿洞入口
                    String key = "cave_" + (surface.getX() / 16) + "_" + (surface.getZ() / 16);
                    if (!progress.containsKey(key)) {
                        progress.put(key, 1);
                        AiMaidMod.LOGGER.debug("[AI-Maid] {} found possible cave at {}", 
                            maid.getGameProfile().getName(), surface);
                    }
                }
            }
        }

        // 3. 其他地标（沙漠神殿、丛林神庙、废弃矿井）
        // 简化：通过方块类型特征识别（如砂岩 = 沙漠神殿）
        // 这里暂不实现，留给后续扩展
    }

    /**
     * 返回家（探索完成后）。
     */
    public static String returnHome(ServerPlayerEntity maid, Map<String, Integer> progress) {
        if (!progress.containsKey("home_x")) {
            return null;  // 没有家
        }

        int homeX = progress.get("home_x");
        int homeZ = progress.get("home_z");
        double dist = Math.hypot(maid.getX() - homeX, maid.getZ() - homeZ);

        if (dist < 5.0) {
            return null;  // 已到家
        }

        MaidBrain.gotoTo(maid, homeX, homeZ);
        return "返回家中（距离 " + (int) dist + " 格）";
    }

    /**
     * 记录一个地标。
     */
    public static void markLandmark(ServerPlayerEntity maid, String name, BlockPos pos) {
        AiMaidMod.LOGGER.info("[AI-Maid] {} marked landmark '{}' at ({}, {}, {})", 
            maid.getGameProfile().getName(), name, pos.getX(), pos.getY(), pos.getZ());
        // 可以存到 NBT 或数据库，这里简化为日志
    }

    /**
     * 获取已知的地标列表。
     */
    public static List<String> getKnownLandmarks(ServerPlayerEntity maid, Map<String, Integer> progress) {
        List<String> landmarks = new ArrayList<>();
        
        if (progress.containsKey("home_x")) {
            landmarks.add("家 (" + progress.get("home_x") + ", " + progress.get("home_z") + ")");
        }
        if (progress.containsKey("village_x")) {
            landmarks.add("村庄 (" + progress.get("village_x") + ", " + progress.get("village_z") + ")");
        }
        
        // 扫描所有 cave_ 前缀的键
        for (String key : progress.keySet()) {
            if (key.startsWith("cave_")) {
                landmarks.add("矿洞 " + key);
            }
        }
        
        return landmarks;
    }
}
