package com.iownmmiku.maid;

import net.minecraft.block.BlockState;
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

    /** 挖掉一个方块。返回 null 表示成功，否则是失败原因。 */
    public static String mine(ServerPlayerEntity maid, BlockPos pos) {
        ServerWorld world = maid.getServerWorld();
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            return "那里是空气";
        }
        if (maid.getPos().distanceTo(Vec3d.ofCenter(pos)) > REACH) {
            return "太远（超过 " + REACH + " 格）";
        }
        lookAt(maid, Vec3d.ofCenter(pos));
        maid.swingHand(Hand.MAIN_HAND);
        boolean ok = maid.interactionManager.tryBreakBlock(pos);
        return ok ? null : "原版互动管理器拒绝了（可能是禁止破坏/受保护区域）";
    }

    /** 在 pos 放一个方块（用主手的物品）。 */
    public static String place(ServerPlayerEntity maid, BlockPos pos) {
        ServerWorld world = maid.getServerWorld();
        ItemStack stack = maid.getMainHandStack();
        if (stack.isEmpty()) {
            return "主手没东西";
        }
        if (!world.getBlockState(pos).isAir() && !world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()) {
            return "目标位置已经被占了";
        }
        BlockPos below = pos.down();
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(below).add(0, 0.5, 0),
                Direction.UP, below, false);
        lookAt(maid, Vec3d.ofCenter(pos));
        ActionResult result = maid.interactionManager.interactBlock(maid, world, stack, Hand.MAIN_HAND, hit);
        return result.isAccepted() ? null : "放置被拒绝：" + result;
    }

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
