package com.iownmmiku.maid;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * 智能挖矿系统：扫描方块找矿、分层挖矿、工具检查。
 */
public final class MaidMining {
    private MaidMining() {
    }

    /**
     * 挖指定的矿石。
     * @param oreBlockId 矿石方块 ID（如 "coal_ore", "iron_ore", "diamond_ore"）
     * @param tool 需要的工具（如 "stone_pickaxe"）
     * @param progress 进度存储（用于记录当前挖矿位置）
     * @return null 表示找到并正在挖，否则返回状态描述
     */
    public static String mineOre(ServerPlayerEntity maid, String oreBlockId, String tool, Map<String, Integer> progress) {
        // 1. 检查工具
        if (MaidActions.countItem(maid, tool) == 0) {
            return "缺少工具：" + tool;
        }
        MaidActions.hold(maid, tool);

        // 2. 找附近的矿石
        Block targetOre = getOreBlock(oreBlockId);
        if (targetOre == null) {
            return "未知矿石：" + oreBlockId;
        }

        BlockPos orePos = findNearbyOre(maid, targetOre, 16);
        if (orePos == null) {
            // 3. 没找到 → 进入分层挖矿模式
            return stripMine(maid, oreBlockId, progress);
        }

        // 4. 找到矿石 → 走过去挖
        double dist = maid.getPos().distanceTo(orePos.toCenterPos());
        if (dist > 5.0) {
            MaidBrain.gotoTo(maid, orePos.getX() + 0.5, orePos.getZ() + 0.5);
            return "前往矿石 (" + orePos.getX() + ", " + orePos.getY() + ", " + orePos.getZ() + ")";
        }

        MaidBrain.stop(maid);
        String err = MaidActions.mine(maid, orePos);
        if (err != null) {
            return "挖矿失败：" + err;
        }
        return "挖掘矿石";
    }

    /**
     * 分层挖矿：按目标矿石的最佳层数，水平挖掘隧道。
     */
    private static String stripMine(ServerPlayerEntity maid, String oreBlockId, Map<String, Integer> progress) {
        // 确定目标层数
        int targetY = getBestMiningLevel(oreBlockId);
        int currentY = maid.getBlockY();

        // 如果不在目标层，先向下/向上移动
        if (Math.abs(currentY - targetY) > 2) {
            if (currentY > targetY) {
                // 向下挖
                BlockPos below = maid.getBlockPos().down();
                MaidActions.mine(maid, below);
                return "向下挖到 Y=" + targetY + "（当前 Y=" + currentY + "）";
            } else {
                // 向上搭方块
                BlockPos above = maid.getBlockPos().up(2);
                if (MaidActions.countItem(maid, "cobblestone") > 0) {
                    MaidActions.hold(maid, "cobblestone");
                    MaidActions.place(maid, above);
                }
                return "向上到 Y=" + targetY;
            }
        }

        // 在目标层开始分层挖矿
        // 策略：沿 X 轴挖一条 2 格高的隧道，每 3 格一个
        int tunnelIndex = progress.getOrDefault("tunnel_index", 0);
        int tunnelLength = progress.getOrDefault("tunnel_length", 0);

        BlockPos tunnelStart = maid.getBlockPos().add(tunnelIndex * 3, 0, 0);
        BlockPos digPos = tunnelStart.add(tunnelLength, 0, 0);

        // 挖前方的方块（2 格高）
        if (tunnelLength < 32) {
            MaidActions.mine(maid, digPos);
            MaidActions.mine(maid, digPos.up());
            progress.put("tunnel_length", tunnelLength + 1);
            
            // 每挖一格都扫描周围是否露出矿石
            BlockPos found = scanTunnelWalls(maid, digPos);
            if (found != null) {
                MaidActions.mine(maid, found);
                return "发现矿石！";
            }
            
            return "分层挖矿 Y=" + targetY + "（隧道 " + tunnelIndex + "，长度 " + tunnelLength + "/32）";
        } else {
            // 当前隧道挖完，开始下一条（间隔 3 格）
            progress.put("tunnel_index", tunnelIndex + 1);
            progress.put("tunnel_length", 0);
            return "切换到隧道 " + (tunnelIndex + 1);
        }
    }

    /**
     * 扫描隧道两侧是否露出矿石。
     */
    private static BlockPos scanTunnelWalls(ServerPlayerEntity maid, BlockPos center) {
        ServerWorld world = maid.getServerWorld();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy >= 0 && dy <= 1 && dz == 0) continue; // 跳过隧道内部
                    BlockPos pos = center.add(dx, dy, dz);
                    Block block = world.getBlockState(pos).getBlock();
                    if (isValuableOre(block)) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 扫描附近是否有目标矿石。
     */
    private static BlockPos findNearbyOre(ServerPlayerEntity maid, Block targetOre, int range) {
        ServerWorld world = maid.getServerWorld();
        BlockPos center = maid.getBlockPos();
        List<BlockPos> candidates = new ArrayList<>();

        for (int dx = -range; dx <= range; dx++) {
            for (int dy = -16; dy <= 16; dy++) {
                for (int dz = -range; dz <= range; dz++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    if (world.getBlockState(pos).getBlock() == targetOre) {
                        candidates.add(pos);
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        // 返回最近的矿石
        candidates.sort(Comparator.comparingDouble(p -> center.getSquaredDistance(p)));
        return candidates.get(0);
    }

    /**
     * 获取矿石的最佳挖掘层数。
     */
    private static int getBestMiningLevel(String oreBlockId) {
        if (oreBlockId.contains("diamond")) {
            return -59;  // 钻石最佳层（1.18+）
        } else if (oreBlockId.contains("iron")) {
            return 16;   // 铁矿富集层
        } else if (oreBlockId.contains("coal")) {
            return 50;   // 煤炭
        } else if (oreBlockId.contains("gold")) {
            return -16;  // 金矿
        } else if (oreBlockId.contains("redstone")) {
            return -59;  // 红石
        } else if (oreBlockId.contains("lapis")) {
            return 0;    // 青金石
        }
        return 0;
    }

    private static Block getOreBlock(String oreBlockId) {
        String id = oreBlockId.contains(":") ? oreBlockId : "minecraft:" + oreBlockId;
        Identifier blockId = new Identifier(id);
        if (!Registries.BLOCK.containsId(blockId)) {
            return null;
        }
        return Registries.BLOCK.get(blockId);
    }

    private static boolean isValuableOre(Block block) {
        return block == Blocks.COAL_ORE || 
               block == Blocks.IRON_ORE || 
               block == Blocks.GOLD_ORE || 
               block == Blocks.DIAMOND_ORE || 
               block == Blocks.EMERALD_ORE || 
               block == Blocks.LAPIS_ORE || 
               block == Blocks.REDSTONE_ORE ||
               block == Blocks.DEEPSLATE_COAL_ORE ||
               block == Blocks.DEEPSLATE_IRON_ORE ||
               block == Blocks.DEEPSLATE_GOLD_ORE ||
               block == Blocks.DEEPSLATE_DIAMOND_ORE ||
               block == Blocks.DEEPSLATE_EMERALD_ORE ||
               block == Blocks.DEEPSLATE_LAPIS_ORE ||
               block == Blocks.DEEPSLATE_REDSTONE_ORE;
    }
}
