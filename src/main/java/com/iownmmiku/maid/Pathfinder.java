package com.iownmmiku.maid;

import net.minecraft.block.BlockState;
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
 * 服务端的 A* 寻路（6 向邻居 + 斜向）。
 * 玩家没有原版寻路（生物才有），所以这一段必须自己写；
 * 但好处是方块数据直接读世界，不需要解析区块。
 */
public final class Pathfinder {
    /** 身体是 2 格高：脚 + 头都要没碰撞。 */
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private Pathfinder() {
    }

    private static boolean empty(ServerWorld w, BlockPos p) {
        BlockState s = w.getBlockState(p);
        return s.getCollisionShape(w, p).isEmpty();
    }

    /** 能站：脚和头都是空的，且脚下是实心。 */
    public static boolean standable(ServerWorld w, BlockPos feet) {
        return empty(w, feet) && empty(w, feet.up()) && !empty(w, feet.down());
    }

    private static double h(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** 在目标那一列附近找一个能站的高度（上下各找 4 格）。 */
    public static BlockPos standableNear(ServerWorld w, int x, int y, int z) {
        BlockPos base = new BlockPos(x, y, z);
        for (int dy = 0; dy <= 4; dy++) {
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
        }
        // 往上跳 1 格
        BlockPos up = new BlockPos(x, cur.getY() + 1, z);
        if (standable(w, up)) {
            out.add(new Object[]{up, baseCost + 0.4});
        }
        // 往下掉 1~3 格
        for (int drop = 1; drop <= 3; drop++) {
            BlockPos dn = new BlockPos(x, cur.getY() - drop, z);
            if (!empty(w, dn) || !empty(w, dn.up())) {
                break;
            }
            if (standable(w, dn)) {
                out.add(new Object[]{dn, baseCost + 0.3 * drop});
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
