package com.iownmmiku.maid;

import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/**
 * 合成系统：检查材料、自动开工作台、合成物品、使用熔炉。
 */
public final class MaidCrafting {
    private MaidCrafting() {
    }

    /**
     * 尝试合成指定物品。会递归合成前置材料。
     * @return null 表示成功，否则返回失败原因
     */
    public static String craft(ServerPlayerEntity maid, String itemId) {
        Recipe.CraftRecipe recipe = Recipe.getCraftRecipe(itemId);
        if (recipe == null) {
            return "没有 " + itemId + " 的配方";
        }

        // 1. 检查并递归合成所需材料
        for (Recipe.Ingredient ing : recipe.ingredients) {
            int have = MaidActions.countItem(maid, ing.item);
            if (have < ing.count) {
                // 尝试递归合成
                String err = craft(maid, ing.item);
                if (err != null) {
                    return "合成 " + itemId + " 失败：缺少 " + ing.item + " x" + (ing.count - have);
                }
                // 再次检查
                have = MaidActions.countItem(maid, ing.item);
                if (have < ing.count) {
                    return "合成 " + itemId + " 失败：" + ing.item + " 不够（需要 " + ing.count + "，只有 " + have + "）";
                }
            }
        }

        // 2. 需要工作台的，先找 / 放 / 造 一个
        BlockPos workbench = null;
        if (recipe.needsWorkbench) {
            workbench = findNearbyBlock(maid, Blocks.CRAFTING_TABLE, 8.0);
            if (workbench == null) {
                // 背包没工作台就先自己造一个（4 木板，2x2 可做，不需要工作台）
                if (MaidActions.countItem(maid, "crafting_table") == 0) {
                    String err = craft(maid, "crafting_table");
                    if (err != null) {
                        return "需要工作台，且造不出来：" + err;
                    }
                }
                // 放到旁边
                BlockPos place = findFreeSpotNear(maid);
                String err = MaidActions.hold(maid, "crafting_table");
                if (err == null && place != null) {
                    err = MaidActions.place(maid, place);
                }
                if (err == null) {
                    workbench = place;
                } else {
                    return "无法放置工作台：" + err;
                }
            }
        }

        // 3. 简化版合成：直接消耗材料 + 给成品（不模拟真实的 GUI 操作）
        for (Recipe.Ingredient ing : recipe.ingredients) {
            if (!consumeItem(maid, ing.item, ing.count)) {
                return "消耗材料失败：" + ing.item;
            }
        }
        String err = MaidActions.give(maid, recipe.result, recipe.resultCount);
        if (err != null) {
            return "合成成功但无法放入背包：" + err;
        }

        AiMaidMod.LOGGER.info("[AI-Maid] {} crafted {} x{}", 
                maid.getGameProfile().getName(), recipe.result, recipe.resultCount);
        return null;
    }

    /**
     * 使用熔炉烧制物品。
     * @param input 输入物品（如 iron_ore）
     * @param count 数量
     * @return null 表示成功
     */
    public static String smelt(ServerPlayerEntity maid, String input, int count) {
        Recipe.SmeltRecipe recipe = Recipe.getSmeltRecipe(input);
        if (recipe == null) {
            return input + " 无法烧制";
        }

        int have = MaidActions.countItem(maid, input);
        if (have < count) {
            return "烧制 " + input + " 失败：只有 " + have + "，需要 " + count;
        }

        // 检查燃料（煤/木板/木头）
        int fuel = MaidActions.countItem(maid, "coal");
        if (fuel < count / 8 + 1) {
            fuel = MaidActions.countItem(maid, "oak_planks");
            if (fuel < count) {
                return "烧制失败：没有燃料（需要煤或木板）";
            }
        }

        // 找或放熔炉
        BlockPos furnacePos = findNearbyBlock(maid, Blocks.FURNACE, 8.0);
        if (furnacePos == null) {
            if (MaidActions.countItem(maid, "furnace") > 0) {
                BlockPos place = maid.getBlockPos().add(1, 0, 0);
                String err = MaidActions.hold(maid, "furnace");
                if (err == null) {
                    err = MaidActions.place(maid, place);
                }
                if (err == null) {
                    furnacePos = place;
                } else {
                    return "无法放置熔炉：" + err;
                }
            } else {
                return "需要熔炉但背包里没有";
            }
        }

        // 简化版：直接消耗材料 + 给成品（不模拟真实的熔炉 GUI）
        if (!consumeItem(maid, input, count)) {
            return "消耗材料失败";
        }
        String fuelItem = MaidActions.countItem(maid, "coal") > 0 ? "coal" : "oak_planks";
        int fuelNeed = (fuelItem.equals("coal")) ? (count / 8 + 1) : count;
        consumeItem(maid, fuelItem, fuelNeed);

        String err = MaidActions.give(maid, recipe.output, count);
        if (err != null) {
            return "烧制成功但无法放入背包：" + err;
        }

        AiMaidMod.LOGGER.info("[AI-Maid] {} smelted {} x{} -> {} x{}", 
                maid.getGameProfile().getName(), input, count, recipe.output, count);
        return null;
    }

    private static boolean consumeItem(ServerPlayerEntity maid, String itemId, int count) {
        Identifier id = itemId.contains(":") ? new Identifier(itemId) : new Identifier("minecraft", itemId);
        if (!Registries.ITEM.containsId(id)) {
            return false;
        }
        Item target = Registries.ITEM.get(id);
        int remain = count;
        PlayerInventory inv = maid.getInventory();
        for (int i = 0; i < inv.size() && remain > 0; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && s.getItem() == target) {
                int take = Math.min(s.getCount(), remain);
                s.decrement(take);
                remain -= take;
            }
        }
        return remain == 0;
    }

    /** 在脚下附近找一个空气方块（用来放工作台/熔炉）。 */
    private static BlockPos findFreeSpotNear(ServerPlayerEntity maid) {
        net.minecraft.server.world.ServerWorld w = maid.getServerWorld();
        BlockPos base = maid.getBlockPos();
        int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}};
        for (int[] o : offsets) {
            BlockPos p = base.add(o[0], 0, o[1]);
            if (w.getBlockState(p).isAir() && !w.getBlockState(p.down()).getCollisionShape(w, p.down()).isEmpty()) {
                return p;
            }
            BlockPos pu = p.up();
            if (w.getBlockState(p).getCollisionShape(w, p).isEmpty()
                    && w.getBlockState(pu).isAir()
                    && !w.getBlockState(p.down()).getCollisionShape(w, p.down()).isEmpty()) {
                return p;
            }
        }
        return base.add(1, 0, 0);
    }

    private static BlockPos findNearbyBlock(ServerPlayerEntity maid, net.minecraft.block.Block block, double range) {
        ServerWorld world = maid.getServerWorld();
        BlockPos center = maid.getBlockPos();
        int r = (int) range;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    if (world.getBlockState(pos).getBlock() == block) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }
}
