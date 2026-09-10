package com.iownmmiku.maid;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.FoodComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;

import java.util.List;

/**
 * 女仆的生存本能：饿了吃、被打了还手、血少了躲。
 */
public final class SurvivalLogic {
    private SurvivalLogic() {
    }

    /** 每 tick 检查生存需求（20 tick 检查一次就够）。 */
    public static void tick(ServerPlayerEntity maid) {
        if (maid.age % 20 != 0) {
            return;
        }
        // 1. 饿了就吃
        if (maid.getHungerManager().getFoodLevel() <= 6 && !maid.getHungerManager().isNotFull()) {
            tryEat(maid);
        }
        // 2. 附近有敌对生物 → 血少就躲，血够就打
        LivingEntity threat = findNearestThreat(maid, 12.0);
        if (threat != null) {
            if (maid.getHealth() < 10.0F) {
                runAway(maid, threat);
            } else {
                fightBack(maid, threat);
            }
        }
    }

    private static void tryEat(ServerPlayerEntity maid) {
        PlayerInventory inv = maid.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            Item item = stack.getItem();
            FoodComponent food = item.getFoodComponent();
            if (food == null) {
                continue;
            }
            // 切到这个槽位并开始吃
            if (i < 9) {
                inv.selectedSlot = i;
            } else {
                // 不在快捷栏 → 和快捷栏第一格交换
                ItemStack tmp = inv.getStack(0);
                inv.setStack(0, stack.copy());
                inv.setStack(i, tmp);
                inv.selectedSlot = 0;
            }
            // 右键物品 → 开始吃（setCurrentHand + 激活使用 tick）
            maid.setCurrentHand(Hand.MAIN_HAND);
            // 强制"吃完"：原版要 tick 32 次，这里直接完成
            ItemStack result = stack.finishUsing(maid.getServerWorld(), maid);
            maid.setStackInHand(Hand.MAIN_HAND, result);
            maid.clearActiveItem();
            AiMaidMod.LOGGER.debug("[AI-Maid] {} ate {}", maid.getGameProfile().getName(), item);
            return;
        }
    }

    private static LivingEntity findNearestThreat(ServerPlayerEntity maid, double range) {
        ServerWorld world = maid.getServerWorld();
        Box box = maid.getBoundingBox().expand(range);
        List<HostileEntity> hostiles = world.getEntitiesByClass(HostileEntity.class, box,
                e -> e.isAlive() && e.canSee(maid));
        if (hostiles.isEmpty()) {
            return null;
        }
        hostiles.sort((a, b) -> Double.compare(a.squaredDistanceTo(maid), b.squaredDistanceTo(maid)));
        return hostiles.get(0);
    }

    private static void runAway(ServerPlayerEntity maid, Entity threat) {
        // 往威胁的反方向跑 16 格
        double dx = maid.getX() - threat.getX();
        double dz = maid.getZ() - threat.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.01) {
            dist = 0.01;
        }
        double escapeX = maid.getX() + (dx / dist) * 16.0;
        double escapeZ = maid.getZ() + (dz / dist) * 16.0;
        MaidBrain.gotoTo(maid, escapeX, escapeZ);
        AiMaidMod.LOGGER.info("[AI-Maid] {} running away from {}", 
                maid.getGameProfile().getName(), threat.getType().getTranslationKey());
    }

    private static void fightBack(ServerPlayerEntity maid, LivingEntity threat) {
        // 面向敌人并攻击
        MaidActions.lookAt(maid, threat.getPos());
        maid.attack(threat);
        maid.swingHand(Hand.MAIN_HAND);
        // 冷却 1 秒再打下一次（原版攻击冷却）
    }
}
