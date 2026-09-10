package com.iownmmiku.maid;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 智能调度器：管理目标队列、优先级、前置检查、进度持久化。
 * 
 * 优先级规则：
 * 1. 生存威胁（血<10 或饿<6）→ 立即处理
 * 2. 当前目标 → 继续执行
 * 3. 选择下一个目标 → 按优先级 + 前置条件
 * 4. 空闲探索 → 没事干就探索
 */
public final class GoalScheduler {
    private static final Map<String, MaidGoals.GoalType> CURRENT_GOAL = new HashMap<>();
    private static final Map<String, Map<String, Integer>> GOAL_PROGRESS = new HashMap<>();
    private static final Map<String, Set<MaidGoals.GoalType>> COMPLETED_GOALS = new HashMap<>();
    /** 在当前环境下无法完成的目标（例如超平坦世界里的挖矿），不重试。 */
    private static final Map<String, Set<MaidGoals.GoalType>> BLOCKED_GOALS = new HashMap<>();
    /** 是否启用 LLM 决策（由插件通过桥开启）。 */
    private static boolean LLM_DECISION_ENABLED = false;
    private static final Set<String> DECISION_PENDING = new HashSet<>();
    private static final Map<String, Long> DECISION_ASKED_AT = new HashMap<>();

    public static void setLlmDecisionEnabled(boolean enabled) {
        LLM_DECISION_ENABLED = enabled;
        AiMaidMod.LOGGER.info("[AI-Maid] LLM 决策：{}", enabled ? "已启用" : "已禁用");
    }

    public static boolean isLlmDecisionEnabled() {
        return LLM_DECISION_ENABLED;
    }

    /** 向外部（AstrBot/LLM）请求下一步决策。 */
    private static void emitDecisionRequest(ServerPlayerEntity maid) {
        String key = key(maid);
        Set<MaidGoals.GoalType> completed = COMPLETED_GOALS.getOrDefault(key, new HashSet<>());
        Set<MaidGoals.GoalType> blocked = BLOCKED_GOALS.getOrDefault(key, new HashSet<>());

        JsonObject ev = new JsonObject();
        ev.addProperty("event", "decision_needed");
        ev.addProperty("who", key);
        ev.addProperty("health", maid.getHealth());
        ev.addProperty("food", maid.getHungerManager().getFoodLevel());
        ev.addProperty("x", maid.getBlockX());
        ev.addProperty("y", maid.getBlockY());
        ev.addProperty("z", maid.getBlockZ());

        JsonArray inv = new JsonArray();
        for (String s : MaidActions.inventory(maid)) inv.add(s);
        ev.add("inventory", inv);

        JsonArray done = new JsonArray();
        for (MaidGoals.GoalType t : completed) done.add(MaidGoals.get(t).name);
        ev.add("completed", done);

        JsonArray avail = new JsonArray();
        for (MaidGoals.Goal g : MaidGoals.getAllGoals()) {
            if (completed.contains(g.type) || blocked.contains(g.type)) continue;
            if (!g.prerequisites.isEmpty()) {
                boolean ready = true;
                for (MaidGoals.GoalType p : g.prerequisites) {
                    if (!completed.contains(p) || blocked.contains(p)) { ready = false; break; }
                }
                if (!ready) continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("id", g.type.name());
            o.addProperty("name", g.name);
            o.addProperty("description", g.description);
            o.addProperty("priority", g.priority);
            avail.add(o);
        }
        ev.add("available_goals", avail);
        BridgeServer.emit(ev);
        AiMaidMod.LOGGER.info("[AI-Maid] 已向 LLM 请求决策（{} 个候选目标）", avail.size());
    }

    private GoalScheduler() {
    }

    /**
     * 主调度循环（每 tick 调用一次）。
     */
    public static void tick(ServerPlayerEntity maid) {
        String key = key(maid);

        // 1. 生存威胁优先（由 SurvivalLogic 处理）
        // 这里不需要额外处理，SurvivalLogic.tick 已经在 MaidBrain 里调用了

        // 2. 检查当前目标
        MaidGoals.GoalType currentType = CURRENT_GOAL.get(key);
        if (currentType != null) {
            MaidGoals.Goal goal = MaidGoals.get(currentType);
            Map<String, Integer> progress = GOAL_PROGRESS.computeIfAbsent(key, k -> new HashMap<>());

            // 执行目标的一步
            String status = GoalExecutor.executeStep(maid, goal, progress);

            if (status == null) {
                // 目标完成（但如果被标记为"无法完成"，不算完成）
                boolean blocked = BLOCKED_GOALS.getOrDefault(key, new HashSet<>()).contains(currentType);
                CURRENT_GOAL.remove(key);
                if (!blocked) {
                    completeGoal(maid, currentType);
                    AiMaidMod.LOGGER.info("[AI-Maid] {} completed goal: {}", key, goal.name);
                }
            } else {
                // 目标进行中
                if (maid.age % 100 == 0) {  // 每 5 秒打印一次进度
                    AiMaidMod.LOGGER.debug("[AI-Maid] {} goal progress: {}", key, status);
                }
            }
            return;
        }

        // 3. 没有当前目标 → 选择下一个（优先问 LLM）
        if (maid.age % 40 == 0) {  // 每 2 秒检查一次
            long now = System.currentTimeMillis();
            if (LLM_DECISION_ENABLED) {
                if (DECISION_PENDING.contains(key)) {
                    // 等 LLM 回复（最多 4 秒）
                    if (now - DECISION_ASKED_AT.getOrDefault(key, 0L) < 4000) {
                        return;
                    }
                    DECISION_PENDING.remove(key);   // 超时 → 规则兜底
                } else {
                    DECISION_PENDING.add(key);
                    DECISION_ASKED_AT.put(key, now);
                    emitDecisionRequest(maid);
                    return;
                }
            }
            MaidGoals.GoalType next = selectNextGoal(maid);
            if (next != null) {
                startGoal(maid, next);
            }
        }
    }

    /**
     * 选择下一个目标。
     */
    private static MaidGoals.GoalType selectNextGoal(ServerPlayerEntity maid) {
        String key = key(maid);

        // 预扫描：目标物品已经满足但还没标记完成的目标 → 直接标记完成（防止背包已有材料导致卡死）
        preAutoComplete(maid, key);

        Set<MaidGoals.GoalType> completed = COMPLETED_GOALS.getOrDefault(key, new HashSet<>());
        Set<MaidGoals.GoalType> blocked = BLOCKED_GOALS.getOrDefault(key, new HashSet<>());

        // 获取所有可执行的目标（前置已完成 + 材料够 + 未被标记无法完成）
        List<MaidGoals.Goal> available = MaidGoals.getAllGoals().stream()
                .filter(g -> !completed.contains(g.type))
                .filter(g -> !blocked.contains(g.type))
                .filter(g -> canStart(maid, g, completed))
                .sorted(Comparator.comparingInt(g -> g.priority))
                .collect(Collectors.toList());

        if (available.isEmpty()) {
            // 所有目标都完成了 → 空闲探索
            return MaidGoals.GoalType.EXPLORE_AREA;
        }

        // TODO: LLM 决策（如果配置了 LLM）
        // String llmChoice = askLLM(maid, available);
        // if (llmChoice != null) { return parseGoalType(llmChoice); }

        // 默认：返回优先级最高的
        return available.get(0).type;
    }

    /** 把"目标物品已拥有"的目标直接标记完成（防止背包已有材料导致生存线卡死）。 */
    private static void preAutoComplete(ServerPlayerEntity maid, String key) {
        Set<MaidGoals.GoalType> done = COMPLETED_GOALS.getOrDefault(key, new HashSet<>());
        for (MaidGoals.Goal g : MaidGoals.getAllGoals()) {
            if (done.contains(g.type) || g.targetItems.isEmpty()) {
                continue;
            }
            boolean allMet = true;
            for (Map.Entry<String, Integer> e : g.targetItems.entrySet()) {
                if (MaidActions.countItem(maid, e.getKey()) < e.getValue()) {
                    allMet = false;
                    break;
                }
            }
            if (allMet) {
                completeGoal(maid, g.type);
                AiMaidMod.LOGGER.info("[AI-Maid] {} auto-completed goal: {} (items already owned)",
                        key, g.name);
            }
        }
    }

    /**
     * 检查目标是否可以开始。
     */
    private static boolean canStart(ServerPlayerEntity maid, MaidGoals.Goal goal, Set<MaidGoals.GoalType> completed) {
        // 1. 检查前置目标（前置被标记无法完成的，后续也跳过）
        Set<MaidGoals.GoalType> blocked = BLOCKED_GOALS.getOrDefault(key(maid), new HashSet<>());
        for (MaidGoals.GoalType prereq : goal.prerequisites) {
            if (!completed.contains(prereq) || blocked.contains(prereq)) {
                return false;
            }
        }

        // 2. 检查必需物品
        for (Map.Entry<String, Integer> entry : goal.requiredItems.entrySet()) {
            if (MaidActions.countItem(maid, entry.getKey()) < entry.getValue()) {
                return false;
            }
        }

        // 3. 检查目标物品（是否已经收集够了）
        boolean allTargetsMet = true;
        for (Map.Entry<String, Integer> entry : goal.targetItems.entrySet()) {
            if (MaidActions.countItem(maid, entry.getKey()) < entry.getValue()) {
                allTargetsMet = false;
                break;
            }
        }
        if (allTargetsMet && !goal.targetItems.isEmpty()) {
            // 目标物品已经够了，直接标记完成
            return false;
        }

        return true;
    }

    /**
     * 启动一个目标。
     */
    private static void startGoal(ServerPlayerEntity maid, MaidGoals.GoalType type) {
        String key = key(maid);
        CURRENT_GOAL.put(key, type);
        GOAL_PROGRESS.computeIfAbsent(key, k -> new HashMap<>()).clear();
        
        MaidGoals.Goal goal = MaidGoals.get(type);
        AiMaidMod.LOGGER.info("[AI-Maid] {} starting goal: {}", key, goal.name);
        
        // 推送事件
        JsonObject ev = new JsonObject();
        ev.addProperty("event", "goal_started");
        ev.addProperty("who", key);
        ev.addProperty("goal", goal.name);
        ev.addProperty("description", goal.description);
        BridgeServer.emit(ev);
    }

    /**
     * 完成一个目标。
     */
    private static void completeGoal(ServerPlayerEntity maid, MaidGoals.GoalType type) {
        String key = key(maid);
        COMPLETED_GOALS.computeIfAbsent(key, k -> new HashSet<>()).add(type);
        
        MaidGoals.Goal goal = MaidGoals.get(type);
        
        // 推送事件
        JsonObject ev = new JsonObject();
        ev.addProperty("event", "goal_completed");
        ev.addProperty("who", key);
        ev.addProperty("goal", goal.name);
        BridgeServer.emit(ev);
    }

    /** 标记一个目标在当前环境下无法完成（不重试、其后续目标也跳过）。 */
    public static void markBlocked(ServerPlayerEntity maid, MaidGoals.GoalType type) {
        String key = key(maid);
        BLOCKED_GOALS.computeIfAbsent(key, k -> new HashSet<>()).add(type);
        MaidGoals.Goal goal = MaidGoals.get(type);
        AiMaidMod.LOGGER.warn("[AI-Maid] {} 目标无法完成，跳过：{}", key, goal.name);

        JsonObject ev = new JsonObject();
        ev.addProperty("event", "goal_blocked");
        ev.addProperty("who", key);
        ev.addProperty("goal", goal.name);
        ev.addProperty("reason", "当前环境无法完成（缺资源/世界类型不匹配）");
        BridgeServer.emit(ev);
    }

    /**
     * 获取当前目标。
     */
    public static MaidGoals.Goal getCurrentGoal(ServerPlayerEntity maid) {
        MaidGoals.GoalType type = CURRENT_GOAL.get(key(maid));
        return type != null ? MaidGoals.get(type) : null;
    }

    /**
     * 获取目标进度。
     */
    public static Map<String, Integer> getProgress(ServerPlayerEntity maid) {
        return GOAL_PROGRESS.getOrDefault(key(maid), new HashMap<>());
    }

    /**
     * 获取已完成的目标。
     */
    public static Set<MaidGoals.GoalType> getCompletedGoals(ServerPlayerEntity maid) {
        return COMPLETED_GOALS.getOrDefault(key(maid), new HashSet<>());
    }

    /**
     * 保存进度到 NBT（用于持久化）。
     */
    public static void saveToNbt(ServerPlayerEntity maid, NbtCompound nbt) {
        String key = key(maid);
        
        // 保存当前目标
        MaidGoals.GoalType current = CURRENT_GOAL.get(key);
        if (current != null) {
            nbt.putString("current_goal", current.name());
        }
        
        // 保存已完成的目标
        Set<MaidGoals.GoalType> completed = COMPLETED_GOALS.get(key);
        if (completed != null) {
            String[] names = completed.stream().map(Enum::name).toArray(String[]::new);
            nbt.putString("completed_goals", String.join(",", names));
        }
        
        // 保存进度
        Map<String, Integer> progress = GOAL_PROGRESS.get(key);
        if (progress != null) {
            NbtCompound progressNbt = new NbtCompound();
            for (Map.Entry<String, Integer> e : progress.entrySet()) {
                progressNbt.putInt(e.getKey(), e.getValue());
            }
            nbt.put("goal_progress", progressNbt);
        }
    }

    /**
     * 从 NBT 加载进度。
     */
    public static void loadFromNbt(ServerPlayerEntity maid, NbtCompound nbt) {
        String key = key(maid);
        
        // 加载当前目标
        if (nbt.contains("current_goal")) {
            String goalName = nbt.getString("current_goal");
            try {
                CURRENT_GOAL.put(key, MaidGoals.GoalType.valueOf(goalName));
            } catch (IllegalArgumentException ignored) {
            }
        }
        
        // 加载已完成的目标
        if (nbt.contains("completed_goals")) {
            String[] names = nbt.getString("completed_goals").split(",");
            Set<MaidGoals.GoalType> completed = new HashSet<>();
            for (String name : names) {
                try {
                    completed.add(MaidGoals.GoalType.valueOf(name));
                } catch (IllegalArgumentException ignored) {
                }
            }
            COMPLETED_GOALS.put(key, completed);
        }
        
        // 加载进度
        if (nbt.contains("goal_progress")) {
            NbtCompound progressNbt = nbt.getCompound("goal_progress");
            Map<String, Integer> progress = new HashMap<>();
            for (String k : progressNbt.getKeys()) {
                progress.put(k, progressNbt.getInt(k));
            }
            GOAL_PROGRESS.put(key, progress);
        }
    }

    /**
     * 手动设置目标（通过桥接口）。
     */
    public static void setGoal(ServerPlayerEntity maid, MaidGoals.GoalType type) {
        DECISION_PENDING.remove(key(maid));   // LLM 已给出决策
        startGoal(maid, type);
    }

    /**
     * 获取目标状态摘要（用于 status 命令）。
     */
    public static String getStatusSummary(ServerPlayerEntity maid) {
        MaidGoals.Goal current = getCurrentGoal(maid);
        if (current == null) {
            return "空闲";
        }
        
        Set<MaidGoals.GoalType> completed = getCompletedGoals(maid);
        int totalSurvival = MaidGoals.getSurvivalPath().size();
        int completedSurvival = (int) completed.stream()
                .filter(g -> MaidGoals.getSurvivalPath().stream().anyMatch(sg -> sg.type == g))
                .count();
        
        return String.format("%s（生存线进度 %d/%d）", current.name, completedSurvival, totalSurvival);
    }

    private static String key(ServerPlayerEntity maid) {
        return maid.getGameProfile().getName().toLowerCase();
    }

    // TODO: LLM 决策接口
    // private static String askLLM(ServerPlayerEntity maid, List<MaidGoals.Goal> available) {
    //     // 构造提示词："我刚完成了 X，现在有 Y 材料，可以做：A/B/C，你建议做什么？"
    //     // 调用 AstrBot 的 LLM API
    //     // 解析回复中的目标名称
    //     return null;
    // }
}
