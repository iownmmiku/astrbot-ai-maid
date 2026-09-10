package com.iownmmiku.maid;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * 服务端的 A* 寻路（6 向邻居 + 斜向 + 破障）。
 * 玩家没有原版寻路（生物才有），所以这一段必须自己写；
 * 但好处是方块数据直接读世界，不需要解析区块。
 *
 * 优化点：
 * 1. 规避危险方块（岩浆/火/仙人掌/浆果丛等）
 * 2. 允许"挖穿"软方块开路（代价高，但能走出死地）
 * 3. 下落最多 4 格，能上下梯形的复杂地形
 */
public final class Pathfinder {
    /** 身体是 2 格高：脚 + 头都要没碰撞。 */
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** 破障代价（挖方块比绕路麻烦，所以权重高）。 */
    private static final double DIG_COST = 12.0;

    private Pathfinder() {
    }

    private static boolean empty(ServerWorld w, BlockPos p) {
        BlockState s = w.getBlockState(p);
        return s.getCollisionShape(w, p).isEmpty();
    }

    /** 危险方块：站上去/走过去会受伤。 */
    public static boolean dangerous(ServerWorld w, BlockPos p) {
        BlockState s = w.getBlockState(p);
        return s.isOf(Blocks.LAVA)
                || s.isOf(Blocks.FIRE)
                || s.isOf(Blocks.SOUL_FIRE)
                || s.isOf(Blocks.CAMPFIRE)
                || s.isOf(Blocks.MAGMA_BLOCK)
                || s.isOf(Blocks.CACTUS)
                || s.isOf(Blocks.SWEET_BERRY_BUSH)
                || s.isOf(Blocks.POWDER_SNOW)
                || s.isOf(Blocks.WITHER_ROSE);
    }

    /** 能不能挖掉（软方块、非基岩、非液体）。 */
    public static boolean diggable(ServerWorld w, BlockPos p) {
        BlockState s = w.getBlockState(p);
        if (s.isAir()) {
            return false;
        }
        if (s.isOf(Blocks.BEDROCK) || s.isOf(Blocks.LAVA) || s.isOf(Blocks.WATER)) {
            return false;
        }
        float hardness = s.getHardness(w, p);
        return hardness >= 0.0F && hardness <= 40.0F;
    }

    /** 能站：脚和头都是空的，且脚下是实心，且不危险。 */
    public static boolean standable(ServerWorld w, BlockPos feet) {
        if (dangerous(w, feet) || dangerous(w, feet.down())) {
            return false;
        }
        return empty(w, feet) && empty(w, feet.up()) && !empty(w, feet.down());
    }

    /** 挖掉 feet 和 feet.up() 后能不能站（破障路径用）。 */
    public static boolean canDigThrough(ServerWorld w, BlockPos feet) {
        if (dangerous(w, feet) || dangerous(w, feet.down())) {
            return false;
        }
        if (empty(w, feet.down())) {
            return false;  // 下面得是实的，挖完才站得住
        }
        BlockPos head = feet.up();
        boolean feetOk = empty(w, feet) || diggable(w, feet);
        boolean headOk = empty(w, head) || diggable(w, head);
        return feetOk && headOk;
    }

    private static double h(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** 在目标那一列附近找一个能站的高度（上下各找 6 格）。 */
    public static BlockPos standableNear(ServerWorld w, int x, int y, int z) {
        BlockPos base = new BlockPos(x, y, z);
        for (int dy = 0; dy <= 6; dy++) {
            BlockPos up = base.up(dy);
            if (standable(w, up)) {
                return up;
            }
            BlockPos down = base.down(dy);
            if (standable(w, down)) {
                return down;
            }
        }
        return null;
    }

    /**
     * @return 从 start（不含）到 goal（含）的路径；到不了返回 null。
     */
    public static List<BlockPos> find(ServerWorld w, BlockPos start, BlockPos goal,
                                      int maxNodes, int maxRadius) {
        if (start.equals(goal)) {
            return new ArrayList<>();
        }
        Map<BlockPos, Double> g = new HashMap<>();
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();
        PriorityQueue<Object[]> open = new PriorityQueue<>(Comparator.comparingDouble(o -> (double) o[0]));

        g.put(start, 0.0);
        open.add(new Object[]{h(start, goal), start});

        int visited = 0;
        while (!open.isEmpty() && visited < maxNodes) {
            Object[] top = open.poll();
            BlockPos cur = (BlockPos) top[1];
            if (!closed.add(cur)) {
                continue;
            }
            visited++;
            if (cur.equals(goal)) {
                return rebuild(cameFrom, cur);
            }
            if (Math.abs(cur.getX() - start.getX()) > maxRadius
                    || Math.abs(cur.getZ() - start.getZ()) > maxRadius) {
                continue;
            }
            double base = g.getOrDefault(cur, Double.MAX_VALUE);

            for (Object[] nb : neighbors(w, cur)) {
                BlockPos n = (BlockPos) nb[0];
                double cost = (double) nb[1];
                double ng = base + cost;
                if (ng < g.getOrDefault(n, Double.MAX_VALUE)) {
                    g.put(n, ng);
                    cameFrom.put(n, cur);
                    open.add(new Object[]{ng + h(n, goal), n});
                }
            }
        }
        return null;
    }

    private static List<Object[]> neighbors(ServerWorld w, BlockPos cur) {
        List<Object[]> out = new ArrayList<>();
        for (int[] d : DIRS) {
            step(w, cur, d[0], d[1], 1.0, out);
        }
        for (int[] d : DIAGS) {
            BlockPos side1 = cur.add(d[0], 0, 0);
            BlockPos side2 = cur.add(0, 0, d[1]);
            if (!empty(w, side1) || !empty(w, side2)) {
                continue;   // 别贴着墙角穿过去
            }
            step(w, cur, d[0], d[1], 1.414, out);
        }
        return out;
    }

    private static void step(ServerWorld w, BlockPos cur, int dx, int dz, double baseCost, List<Object[]> out) {
        int x = cur.getX() + dx;
        int z = cur.getZ() + dz;

        // 平走
        BlockPos flat = new BlockPos(x, cur.getY(), z);
        if (standable(w, flat)) {
            out.add(new Object[]{flat, baseCost});
        } else if (canDigThrough(w, flat)) {
            out.add(new Object[]{flat, baseCost + DIG_COST});
        }

        // 往上跳 1 格
        BlockPos up = new BlockPos(x, cur.getY() + 1, z);
        if (standable(w, up)) {
            out.add(new Object[]{up, baseCost + 0.4});
        } else if (canDigThrough(w, up)) {
            out.add(new Object[]{up, baseCost + DIG_COST + 0.4});
        }

        // 往上挖 2 格（爬升开路）
        BlockPos up2 = new BlockPos(x, cur.getY() + 2, z);
        if (standable(w, up2)) {
            out.add(new Object[]{up2, baseCost + 1.2});
        }

        // 往下掉 1~4 格
        for (int drop = 1; drop <= 4; drop++) {
            BlockPos dn = new BlockPos(x, cur.getY() - drop, z);
            if (dangerous(w, dn) || dangerous(w, dn.down())) {
                break;
            }
            if (!empty(w, dn) || !empty(w, dn.up())) {
                break;
            }
            if (standable(w, dn)) {
                out.add(new Object[]{dn, baseCost + 0.25 * drop});
                break;
            }
        }
    }

    private static List<BlockPos> rebuild(Map<BlockPos, BlockPos> cameFrom, BlockPos end) {
        List<BlockPos> path = new ArrayList<>();
        BlockPos cur = end;
        while (cur != null) {
            path.add(cur);
            cur = cameFrom.get(cur);
        }
        java.util.Collections.reverse(path);
        if (!path.isEmpty()) {
            path.remove(0);   // 去掉起点
        }
        return path;
    }
}
