package com.iownmmiku.maid;

import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * 建筑任务：给一个方块列表，女仆按顺序走过去、拿对应物品、放下。
 * 
 * 顺序：从下往上、从近到远（地基先铺，离得近的先放）。
 */
public final class BuildTask {
    public static class Placement {
        public final BlockPos pos;
        public final String blockId;  // "minecraft:stone"
        public boolean done;

        public Placement(BlockPos pos, String blockId) {
            this.pos = pos;
            this.blockId = blockId;
        }
    }

    private final List<Placement> plan;
    private int cursor = 0;
    private String error = null;

    public BuildTask(List<Placement> plan) {
        this.plan = plan;
    }

    public boolean isDone() {
        return cursor >= plan.size() || error != null;
    }

    public String getError() {
        return error;
    }

    public Placement current() {
        if (cursor >= plan.size()) {
            return null;
        }
        return plan.get(cursor);
    }

    public void advance() {
        if (cursor < plan.size()) {
            plan.get(cursor).done = true;
            cursor++;
        }
    }

    public void fail(String reason) {
        this.error = reason;
    }

    public int total() {
        return plan.size();
    }

    public int progress() {
        return cursor;
    }

    /** 按"从下到上、从近到远"排序蓝图。 */
    public static List<Placement> sortPlan(ServerPlayerEntity maid, List<Placement> raw) {
        List<Placement> sorted = new ArrayList<>(raw);
        BlockPos start = maid.getBlockPos();
        sorted.sort((a, b) -> {
            // 先按 Y（从下到上）
            if (a.pos.getY() != b.pos.getY()) {
                return Integer.compare(a.pos.getY(), b.pos.getY());
            }
            // 同高度按距离
            double da = start.getSquaredDistance(a.pos);
            double db = start.getSquaredDistance(b.pos);
            return Double.compare(da, db);
        });
        return sorted;
    }

    /** 检查背包里有没有足够的材料。返回 null 表示够，否则返回缺什么。 */
    public static String checkMaterials(ServerPlayerEntity maid, List<Placement> plan) {
        Map<String, Integer> need = new HashMap<>();
        for (Placement p : plan) {
            need.put(p.blockId, need.getOrDefault(p.blockId, 0) + 1);
        }
        for (Map.Entry<String, Integer> e : need.entrySet()) {
            String id = e.getKey();
            int count = e.getValue();
            Identifier itemId = new Identifier(id);
            if (!Registries.ITEM.containsId(itemId)) {
                return "未知物品：" + id;
            }
            int has = MaidActions.countItem(maid, id);
            if (has < count) {
                return "缺少 " + id + " x" + (count - has) + "（需要 " + count + "，只有 " + has + "）";
            }
        }
        return null;
    }
}
