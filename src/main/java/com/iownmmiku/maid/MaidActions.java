package com.iownmmiku.maid;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.hit.BlockHitResult;

import java.util.ArrayList;
import java.util.List;

/** 女仆的动作：看、挖、放、攻击、拿东西。全部走原版互动管理器。 */
public final class MaidActions {
    private static final double REACH = 5.0;

    private MaidActions() {
    }

    public static void lookAt(ServerPlayerEntity maid, Vec3d target) {
        double dx = target.x - maid.getX();
        double dy = target.y - (maid.getY() + maid.getStandingEyeHeight());
        double dz = target.z - maid.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        maid.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        maid.setHeadYaw(maid.getYaw());
        maid.setPitch((float) -Math.toDegrees(Math.atan2(dy, flat)));
    }

    /**
     * 根据方块类型，从快捷栏选最合适的工具放到手里。
     * 没合适的就用当前手上的（可能徒手）。
     */
    public static void equipBestTool(ServerPlayerEntity maid, BlockPos pos) {
        ServerWorld w = maid.getServerWorld();
        BlockState state = w.getBlockState(pos);
        PlayerInventory inv = maid.getInventory();

        int best = -1;
        float bestScore = 1.0F;
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) {
                continue;
            }
            float score = s.getMiningSpeedMultiplier(state);
            if (s.isSuitableFor(state)) {
                score += 100.0F;   // 合适工具优先
            }
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (best >= 0) {
            inv.selectedSlot = best;
        }
    }

    /** 挖掉一个方块（自动选工具）。返回 null 表示成功，否则是失败原因。 */
    public static String mine(ServerPlayerEntity maid, BlockPos pos) {
        ServerWorld world = maid.getServerWorld();
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            return "那里是空气";
        }
        if (maid.getPos().distanceTo(Vec3d.ofCenter(pos)) > REACH) {
            return "太远（超过 " + REACH + " 格）";
        }
        equipBestTool(maid, pos);
        lookAt(maid, Vec3d.ofCenter(pos));
        maid.swingHand(Hand.MAIN_HAND);
        boolean ok = maid.interactionManager.tryBreakBlock(pos);
        return ok ? null : "原版互动管理器拒绝了（可能是禁止破坏/受保护区域）";
    }

    /** 在 pos 放一个方块（用主手的物品），自动找可用的支撑面。 */
    public static String place(ServerPlayerEntity maid, BlockPos pos) {
        ServerWorld world = maid.getServerWorld();
        ItemStack stack = maid.getMainHandStack();
        if (stack.isEmpty()) {
            return "主手没东西";
        }
        BlockState at = world.getBlockState(pos);
        if (!at.isAir() && !at.getCollisionShape(world, pos).isEmpty()) {
            return "目标位置已经被占了";
        }

        // 依次尝试：下、上、北、南、西、东 六个面
        Direction[] sides = {Direction.UP, Direction.DOWN, Direction.NORTH,
                Direction.SOUTH, Direction.WEST, Direction.EAST};
        for (Direction d : sides) {
            BlockPos support = pos.offset(d);
            BlockState ss = world.getBlockState(support);
            // 支撑面得是实心的
            if (ss.getCollisionShape(world, support).isEmpty()) {
                continue;
            }
            // 别贴着危险物放
            if (ss.isOf(Blocks.LAVA) || ss.isOf(Blocks.FIRE)
                    || ss.isOf(Blocks.SOUL_FIRE) || ss.isOf(Blocks.CAMPFIRE)) {
                continue;
            }
            BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(support),
                    d.getOpposite(), support, false);
            lookAt(maid, Vec3d.ofCenter(pos));
            ActionResult result = maid.interactionManager.interactBlock(
                    maid, world, stack, Hand.MAIN_HAND, hit);
            if (result.isAccepted()) {
                return null;
            }
        }
        return "放置被拒绝（没有可用的支撑面）";
    }

    /** 攻击一个实体（贴脸才有伤害）。返回是否打到了。 */
    public static boolean attack(ServerPlayerEntity maid, Entity target) {
        if (maid.distanceTo(target) > 3.5) {
            return false;
        }
        lookAt(maid, target.getPos());
        maid.swingHand(Hand.MAIN_HAND);
        maid.attack(target);
        return true;
    }

    /** 用副手或主手换成盾牌/食物等（预留）。 */

    public static String give(ServerPlayerEntity maid, String itemName, int count) {
        Identifier id = itemName.contains(":") ? new Identifier(itemName)
                : new Identifier("minecraft", itemName);
        if (!Registries.ITEM.containsId(id)) {
            return "没有这个物品：" + itemName;
        }
        Item item = Registries.ITEM.get(id);
        ItemStack stack = new ItemStack(item, count);
        PlayerInventory inv = maid.getInventory();
        boolean ok = inv.insertStack(stack);
        return ok && stack.isEmpty() ? null : "背包塞不下（剩余 " + stack.getCount() + "）";
    }

    /** 让主手拿某样东西：先找背包里有没有。 */
    public static String hold(ServerPlayerEntity maid, String itemName) {
        Identifier id = itemName.contains(":") ? new Identifier(itemName)
                : new Identifier("minecraft", itemName);
        PlayerInventory inv = maid.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && Registries.ITEM.getId(s.getItem()).equals(id)) {
                if (i < 9) {
                    inv.selectedSlot = i;
                } else {
                    inv.swapSlotWithHotbar(i);
                }
                return null;
            }
        }
        return "背包里没有 " + itemName;
    }

    public static List<String> inventory(ServerPlayerEntity maid) {
        List<String> out = new ArrayList<>();
        PlayerInventory inv = maid.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty()) {
                out.add(i + ":" + Registries.ITEM.getId(s.getItem()).getPath() + " x" + s.getCount());
            }
        }
        ItemStack off = maid.getOffHandStack();
        if (!off.isEmpty()) {
            out.add("off:" + Registries.ITEM.getId(off.getItem()).getPath() + " x" + off.getCount());
        }
        return out;
    }

    /** 统计背包里某物品的总数。 */
    public static int countItem(ServerPlayerEntity maid, String itemId) {
        Identifier id = itemId.contains(":") ? new Identifier(itemId) : new Identifier("minecraft", itemId);
        if (!Registries.ITEM.containsId(id)) {
            return 0;
        }
        Item target = Registries.ITEM.get(id);
        int total = 0;
        PlayerInventory inv = maid.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && s.getItem() == target) {
                total += s.getCount();
            }
        }
        ItemStack off = maid.getOffHandStack();
        if (!off.isEmpty() && off.getItem() == target) {
            total += off.getCount();
        }
        return total;
    }
}
