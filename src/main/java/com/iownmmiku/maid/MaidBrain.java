package com.iownmmiku.maid;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 女仆的"手脚"：每 tick 喂移动输入，物理交给原版（tickMovement）。
 * 有路径就沿路径走，没有就直线走。
 */
public final class MaidBrain {
    private static final Map<String, List<BlockPos>> PATH = new HashMap<>();
    private static final Map<String, Integer> PATH_IDX = new HashMap<>();
    private static final Map<String, double[]> TARGETS = new HashMap<>();
    private static final Map<String, Integer> JUMP_TICKS = new HashMap<>();
    private static final Map<String, BuildTask> BUILD_TASKS = new HashMap<>();

    private MaidBrain() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(MaidBrain::onTick);
    }

    /** 直线走过去（不走寻路）。 */
    public static void walkTo(ServerPlayerEntity maid, double x, double z) {
        PATH.remove(key(maid));
        PATH_IDX.remove(key(maid));
        TARGETS.put(key(maid), new double[]{x, z});
    }

    /** 走 A* 路径。返回路径长度；-1 表示找不到路（已回退成直线）。 */
    public static int gotoTo(ServerPlayerEntity maid, double x, double z) {
        String k = key(maid);
        ServerWorld w = maid.getServerWorld();
        BlockPos start = maid.getBlockPos();
        BlockPos goal = Pathfinder.standableNear(w, (int) Math.floor(x),
                (int) Math.floor(maid.getY()), (int) Math.floor(z));
        if (goal == null) {
            walkTo(maid, x, z);
            return -1;
        }
        List<BlockPos> path = Pathfinder.find(w, start, goal, 6000, 48);
        if (path == null) {
            walkTo(maid, x, z);
            return -1;
        }
        PATH.put(k, path);
        PATH_IDX.put(k, 0);
        TARGETS.put(k, new double[]{x, z});
        AiMaidMod.LOGGER.info("[AI-Maid] {} path: {} nodes -> ({}, {}, {})",
                k, path.size(), goal.getX(), goal.getY(), goal.getZ());
        return path.size();
    }

    public static void stop(ServerPlayerEntity maid) {
        String k = key(maid);
        PATH.remove(k);
        PATH_IDX.remove(k);
        TARGETS.remove(k);
        BUILD_TASKS.remove(k);
        maid.forwardSpeed = 0.0F;
        maid.sidewaysSpeed = 0.0F;
    }

    public static void jumpOnce(ServerPlayerEntity maid) {
        JUMP_TICKS.put(key(maid), 4);
    }

    public static String describe(ServerPlayerEntity maid) {
        String k = key(maid);
        BuildTask bt = BUILD_TASKS.get(k);
        if (bt != null) {
            return "building " + bt.progress() + "/" + bt.total();
        }
        List<BlockPos> path = PATH.get(k);
        if (path != null) {
            int idx = PATH_IDX.getOrDefault(k, 0);
            return "path " + Math.min(idx, path.size()) + "/" + path.size();
        }
        double[] t = TARGETS.get(k);
        if (t == null) {
            return "idle";
        }
        return String.format("straight -> (%.1f, %.1f) %.2f left",
                t[0], t[1], Math.hypot(t[0] - maid.getX(), t[1] - maid.getZ()));
    }

    public static void startBuild(ServerPlayerEntity maid, BuildTask task) {
        String k = key(maid);
        BUILD_TASKS.put(k, task);
        stop(maid);  // 清掉移动任务
    }

    private static String key(ServerPlayerEntity maid) {
        return maid.getGameProfile().getName().toLowerCase();
    }

    private static void tickBuild(ServerPlayerEntity maid, BuildTask task) {
        BuildTask.Placement cur = task.current();
        if (cur == null) {
            return;
        }
        BlockPos target = cur.pos;
        double dist = Math.hypot(target.getX() + 0.5 - maid.getX(), target.getZ() + 0.5 - maid.getZ());

        // 离得远就先走过去
        if (dist > 4.5) {
            gotoTo(maid, target.getX() + 0.5, target.getZ() + 0.5);
            return;
        }
        // 停下来准备放
        stop(maid);

        // 手上拿对的方块
        String need = cur.blockId.replace("minecraft:", "");
        ItemStack hand = maid.getMainHandStack();
        if (hand.isEmpty() || !Registries.ITEM.getId(hand.getItem()).getPath().equals(need)) {
            String err = MaidActions.hold(maid, need);
            if (err != null) {
                task.fail(err);
                return;
            }
        }
        // 放下
        String err = MaidActions.place(maid, target);
        if (err == null) {
            task.advance();
            MaidEvents.placed(maid, target);
            AiMaidMod.LOGGER.debug("[AI-Maid] {} placed {} at ({},{},{})",
                    key(maid), cur.blockId, target.getX(), target.getY(), target.getZ());
        } else {
            task.fail(err);
        }
    }

    private static void onTick(MinecraftServer server) {
        for (ServerPlayerEntity maid : Maids.all().values()) {
            String k = key(maid);

            // 1. 生存本能（饿了吃、打怪、躲）
            SurvivalLogic.tick(maid);

            // 2. 建筑任务优先
            BuildTask bt = BUILD_TASKS.get(k);
            if (bt != null) {
                if (bt.isDone()) {
                    if (bt.getError() != null) {
                        AiMaidMod.LOGGER.warn("[AI-Maid] {} build failed: {}", k, bt.getError());
                        MaidEvents.buildFailed(maid, bt.getError());
                    } else {
                        AiMaidMod.LOGGER.info("[AI-Maid] {} build complete ({} blocks)", k, bt.total());
                        MaidEvents.buildComplete(maid, bt.total());
                    }
                    BUILD_TASKS.remove(k);
                } else {
                    tickBuild(maid, bt);
                }
                maid.tickMovement();
                continue;
            }

            // 3. 跳跃
            Integer jt = JUMP_TICKS.get(k);
            if (jt != null && jt > 0) {
                maid.setJumping(true);
                if (jt <= 1) {
                    JUMP_TICKS.remove(k);
                } else {
                    JUMP_TICKS.put(k, jt - 1);
                }
            } else {
                maid.setJumping(false);
            }

            // 4. 移动（路径 / 直线）
            double goalX;
            double goalZ;

            List<BlockPos> path = PATH.get(k);
            if (path != null) {
                int idx = PATH_IDX.getOrDefault(k, 0);
                if (idx >= path.size()) {
                    stop(maid);
                    AiMaidMod.LOGGER.info("[AI-Maid] {} arrived (path end)", k);
                    MaidEvents.arrived(maid, maid.getX(), maid.getY(), maid.getZ());
                    maid.tickMovement();
                    continue;
                }
                BlockPos wp = path.get(idx);
                goalX = wp.getX() + 0.5;
                goalZ = wp.getZ() + 0.5;
                double flatDist = Math.hypot(goalX - maid.getX(), goalZ - maid.getZ());
                double dy = wp.getY() - maid.getY();
                if (flatDist < 0.6 && dy > -1.2) {
                    PATH_IDX.put(k, idx + 1);
                }
                if (dy > 0.3 && flatDist < 1.6 && maid.isOnGround()) {
                    jumpOnce(maid);   // 该上台阶了
                }
            } else {
                double[] t = TARGETS.get(k);
                if (t == null) {
                    maid.forwardSpeed = 0.0F;
                    maid.sidewaysSpeed = 0.0F;
                    maid.tickMovement();
                    continue;
                }
                goalX = t[0];
                goalZ = t[1];
                if (Math.hypot(goalX - maid.getX(), goalZ - maid.getZ()) < 1.0) {
                    stop(maid);
                    AiMaidMod.LOGGER.info("[AI-Maid] {} arrived ({}, {})", k, t[0], t[1]);
                    MaidEvents.arrived(maid, maid.getX(), maid.getY(), maid.getZ());
                    maid.tickMovement();
                    continue;
                }
                if (maid.horizontalCollision && maid.isOnGround()) {
                    jumpOnce(maid);
                }
            }

            double dx = goalX - maid.getX();
            double dz = goalZ - maid.getZ();
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            maid.setYaw(yaw);
            maid.setHeadYaw(yaw);
            maid.forwardSpeed = 1.0F;
            maid.sidewaysSpeed = 0.0F;

            // 服务器不替玩家跑物理，必须自己调
            maid.tickMovement();
        }
    }
}
