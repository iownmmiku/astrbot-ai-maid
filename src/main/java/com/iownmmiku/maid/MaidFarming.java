package com.iownmmiku.maid;

import net.minecraft.block.*;
import net.minecraft.item.HoeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/**
 * 种田系统：锄地 → 种种子 → 浇水 → 收割成熟作物。
 */
public final class MaidFarming {
    private MaidFarming() {
    }

    /**
     * 种植作物：找一块地，锄地，种种子。
     */
    public static String plantCrops(ServerPlayerEntity maid, Map<String, Integer> progress) {
        // 1. 检查是否有种子
        int seeds = MaidActions.countItem(maid, "wheat_seeds");
        if (seeds == 0) {
            // 尝试从小麦获取种子（破坏草方块也能掉种子，但这里简化）
            return "缺少种子（需要 wheat_seeds）";
        }

        // 2. 检查是否有锄头
        String hoe = findHoe(maid);
        if (hoe == null) {
            return "缺少锄头（需要任意锄头）";
        }

        // 3. 找一块平地
        BlockPos farmSpot = progress.containsKey("farm_x") ? 
            new BlockPos(progress.get("farm_x"), progress.get("farm_y"), progress.get("farm_z")) :
            findFarmingSpot(maid);

        if (farmSpot == null) {
            return "找不到合适的耕地位置";
        }

        progress.put("farm_x", farmSpot.getX());
        progress.put("farm_y", farmSpot.getY());
        progress.put("farm_z", farmSpot.getZ());

        // 4. 走过去
        double dist = maid.getPos().distanceTo(farmSpot.toCenterPos());
        if (dist > 4.0) {
            MaidBrain.gotoTo(maid, farmSpot.getX() + 0.5, farmSpot.getZ() + 0.5);
            return "前往农田";
        }

        MaidBrain.stop(maid);
        ServerWorld world = maid.getServerWorld();
        BlockState state = world.getBlockState(farmSpot);

        // 5. 如果是草地/泥土，先锄地
        if (state.getBlock() == Blocks.GRASS_BLOCK || state.getBlock() == Blocks.DIRT) {
            MaidActions.hold(maid, hoe);
            // 右键锄地
            BlockHitResult hit = new BlockHitResult(
                farmSpot.toCenterPos(), Direction.UP, farmSpot, false);
            maid.interactionManager.interactBlock(maid, world, 
                maid.getStackInHand(Hand.MAIN_HAND), Hand.MAIN_HAND, hit);
            return "锄地";
        }

        // 6. 如果是耕地，种种子
        if (state.getBlock() == Blocks.FARMLAND) {
            BlockPos above = farmSpot.up();
            if (world.getBlockState(above).isAir()) {
                MaidActions.hold(maid, "wheat_seeds");
                MaidActions.place(maid, above);
                int planted = progress.getOrDefault("planted", 0);
                progress.put("planted", planted + 1);
                
                // 种完一块，移到下一块
                progress.remove("farm_x");
                progress.remove("farm_y");
                progress.remove("farm_z");
                
                if (planted + 1 >= 9) {
                    return null;  // 完成（9 块地）
                }
                return "种植 " + (planted + 1) + "/9";
            }
        }

        return "准备种植";
    }

    /**
     * 收割成熟的作物。
     */
    public static String harvestCrops(ServerPlayerEntity maid, Map<String, Integer> progress) {
        // 扫描附近的成熟作物
        BlockPos crop = findMatureCrop(maid, 16);
        if (crop == null) {
            return null;  // 没有成熟作物，任务完成
        }

        // 走过去收割
        double dist = maid.getPos().distanceTo(crop.toCenterPos());
        if (dist > 4.0) {
            MaidBrain.gotoTo(maid, crop.getX() + 0.5, crop.getZ() + 0.5);
            return "前往作物";
        }

        MaidBrain.stop(maid);
        MaidActions.mine(maid, crop);
        
        // 收割后重新种植
        ServerWorld world = maid.getServerWorld();
        if (world.getBlockState(crop).isAir() && 
            MaidActions.countItem(maid, "wheat_seeds") > 0) {
            MaidActions.hold(maid, "wheat_seeds");
            MaidActions.place(maid, crop);
        }

        int harvested = progress.getOrDefault("harvested", 0);
        progress.put("harvested", harvested + 1);
        return "收割作物 " + (harvested + 1);
    }

    /**
     * 找一个适合种田的位置（平坦的草地/泥土，附近有水）。
     */
    private static BlockPos findFarmingSpot(ServerPlayerEntity maid) {
        ServerWorld world = maid.getServerWorld();
        BlockPos center = maid.getBlockPos();

        for (int dx = -16; dx <= 16; dx++) {
            for (int dz = -16; dz <= 16; dz++) {
                BlockPos pos = center.add(dx, 0, dz);
                BlockState state = world.getBlockState(pos);
                
                // 必须是草地或泥土
                if (state.getBlock() != Blocks.GRASS_BLOCK && state.getBlock() != Blocks.DIRT) {
                    continue;
                }
                
                // 上方必须是空气
                if (!world.getBlockState(pos.up()).isAir()) {
                    continue;
                }
                
                // 附近 4 格内有水源（耕地需要水）
                if (hasWaterNearby(world, pos, 4)) {
                    return pos;
                }
            }
        }
        return null;
    }

    /**
     * 检查附近是否有水。
     */
    private static boolean hasWaterNearby(ServerWorld world, BlockPos center, int range) {
        for (int dx = -range; dx <= range; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -range; dz <= range; dz++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    if (world.getBlockState(pos).getBlock() == Blocks.WATER) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 找成熟的作物。
     */
    private static BlockPos findMatureCrop(ServerPlayerEntity maid, int range) {
        ServerWorld world = maid.getServerWorld();
        BlockPos center = maid.getBlockPos();

        for (int dx = -range; dx <= range; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -range; dz <= range; dz++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    BlockState state = world.getBlockState(pos);
                    
                    // 检查是否是作物且已成熟
                    if (state.getBlock() instanceof CropBlock) {
                        CropBlock crop = (CropBlock) state.getBlock();
                        if (crop.isMature(state)) {
                            return pos;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * 找背包里的锄头。
     */
    private static String findHoe(ServerPlayerEntity maid) {
        String[] hoes = {"wooden_hoe", "stone_hoe", "iron_hoe", "golden_hoe", "diamond_hoe"};
        for (String hoe : hoes) {
            if (MaidActions.countItem(maid, hoe) > 0) {
                return hoe;
            }
        }
        return null;
    }

    /**
     * 定期巡查农田（由调度系统调用）。
     */
    public static void tickFarms(ServerPlayerEntity maid) {
        // 每 100 tick 检查一次农田
        if (maid.age % 100 != 0) {
            return;
        }

        // 找附近的成熟作物
        BlockPos crop = findMatureCrop(maid, 16);
        if (crop != null) {
            // 有成熟作物 → 启动收割目标
            AiMaidMod.LOGGER.debug("[AI-Maid] {} found mature crop at {}", 
                maid.getGameProfile().getName(), crop);
            // 这里可以触发一个"收割"目标，或者直接收割
        }

        // 检查耕地是否缺水（耕地会变回泥土）
        // 简化：假设玩家已经在附近放了水源
    }
}
