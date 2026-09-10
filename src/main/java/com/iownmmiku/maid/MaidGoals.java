package com.iownmmiku.maid;

import java.util.*;

/**
 * 目标定义：从零到钻石装备的完整生存线。
 * 每个目标有：名称、描述、前置条件、完成条件、执行逻辑。
 */
public final class MaidGoals {
    public enum GoalType {
        // 生存线
        GATHER_WOOD,        // 采集木头 16
        CRAFT_WOODEN_TOOLS, // 合成木工具（镐、斧）
        GATHER_COBBLE,      // 挖石头 64
        CRAFT_STONE_TOOLS,  // 合成石工具 + 熔炉
        GATHER_COAL,        // 挖煤 32
        GATHER_IRON_ORE,    // 挖铁矿 24
        SMELT_IRON,         // 烧铁锭
        CRAFT_IRON_GEAR,    // 合成铁装备（镐、剑、护甲）
        GATHER_DIAMOND,     // 挖钻石 3
        CRAFT_DIAMOND_TOOLS,// 合成钻石镐、剑
        
        // 建筑线
        BUILD_SHELTER,      // 盖庇护所（7x4x7 小屋）
        BUILD_FARM,         // 建造农场（9x9）
        DECORATE_HOME,      // 装饰家园
        
        // 探索线
        EXPLORE_AREA,       // 探索附近区域
        FIND_VILLAGE,       // 寻找村庄
        
        // 生活线
        HUNT_FOOD,          // 狩猎食物
        COOK_FOOD,          // 烹饪食物
        PLANT_CROPS,        // 种植作物
        HARVEST_CROPS,      // 收割作物
    }

    public static class Goal {
        public final GoalType type;
        public final String name;
        public final String description;
        public final List<GoalType> prerequisites;  // 前置目标
        public final Map<String, Integer> requiredItems;  // 需要的物品
        public final Map<String, Integer> targetItems;    // 要收集的物品
        public final int priority;  // 优先级（数字越小越优先）

        public Goal(GoalType type, String name, String description, int priority) {
            this.type = type;
            this.name = name;
            this.description = description;
            this.priority = priority;
            this.prerequisites = new ArrayList<>();
            this.requiredItems = new HashMap<>();
            this.targetItems = new HashMap<>();
        }

        public Goal requires(GoalType... goals) {
            prerequisites.addAll(Arrays.asList(goals));
            return this;
        }

        public Goal needsItem(String item, int count) {
            requiredItems.put(item, count);
            return this;
        }

        public Goal target(String item, int count) {
            targetItems.put(item, count);
            return this;
        }
    }

    private static final Map<GoalType, Goal> GOALS = new HashMap<>();

    static {
        // === 生存线 ===
        GOALS.put(GoalType.GATHER_WOOD, new Goal(
            GoalType.GATHER_WOOD, "采集木头", "收集 16 个原木", 100
        ).target("oak_log", 16));

        GOALS.put(GoalType.CRAFT_WOODEN_TOOLS, new Goal(
            GoalType.CRAFT_WOODEN_TOOLS, "制作木工具", "合成木镐和木斧", 110
        ).requires(GoalType.GATHER_WOOD)
         .target("wooden_pickaxe", 1)
         .target("wooden_axe", 1));

        GOALS.put(GoalType.GATHER_COBBLE, new Goal(
            GoalType.GATHER_COBBLE, "挖掘石头", "收集 64 个圆石", 120
        ).requires(GoalType.CRAFT_WOODEN_TOOLS)
         .needsItem("wooden_pickaxe", 1)
         .target("cobblestone", 64));

        GOALS.put(GoalType.CRAFT_STONE_TOOLS, new Goal(
            GoalType.CRAFT_STONE_TOOLS, "制作石工具", "合成石镐、石斧、石剑、熔炉、工作台", 130
        ).requires(GoalType.GATHER_COBBLE)
         .target("stone_pickaxe", 1)
         .target("stone_axe", 1)
         .target("stone_sword", 1)
         .target("furnace", 1)
         .target("crafting_table", 1));

        GOALS.put(GoalType.GATHER_COAL, new Goal(
            GoalType.GATHER_COAL, "挖掘煤炭", "收集 32 个煤炭（用于烧铁和照明）", 140
        ).requires(GoalType.CRAFT_STONE_TOOLS)
         .needsItem("stone_pickaxe", 1)
         .target("coal", 32));

        GOALS.put(GoalType.GATHER_IRON_ORE, new Goal(
            GoalType.GATHER_IRON_ORE, "挖掘铁矿", "收集 24 个铁矿石（需要石镐）", 150
        ).requires(GoalType.CRAFT_STONE_TOOLS)
         .needsItem("stone_pickaxe", 1)
         .target("raw_iron", 24));

        GOALS.put(GoalType.SMELT_IRON, new Goal(
            GoalType.SMELT_IRON, "冶炼铁锭", "烧制 24 个铁锭", 160
        ).requires(GoalType.GATHER_IRON_ORE, GoalType.GATHER_COAL)
         .needsItem("raw_iron", 24)
         .needsItem("coal", 3)
         .target("iron_ingot", 24));

        GOALS.put(GoalType.CRAFT_IRON_GEAR, new Goal(
            GoalType.CRAFT_IRON_GEAR, "制作铁装备", "合成铁镐、铁剑、铁甲", 170
        ).requires(GoalType.SMELT_IRON)
         .target("iron_pickaxe", 1)
         .target("iron_sword", 1)
         .target("iron_helmet", 1)
         .target("iron_chestplate", 1)
         .target("iron_leggings", 1)
         .target("iron_boots", 1));

        GOALS.put(GoalType.GATHER_DIAMOND, new Goal(
            GoalType.GATHER_DIAMOND, "挖掘钻石", "收集 3 个钻石（需要铁镐，Y=-59 最佳）", 180
        ).requires(GoalType.CRAFT_IRON_GEAR)
         .needsItem("iron_pickaxe", 1)
         .target("diamond", 3));

        GOALS.put(GoalType.CRAFT_DIAMOND_TOOLS, new Goal(
            GoalType.CRAFT_DIAMOND_TOOLS, "制作钻石工具", "合成钻石镐和钻石剑", 190
        ).requires(GoalType.GATHER_DIAMOND)
         .target("diamond_pickaxe", 1)
         .target("diamond_sword", 1));

        // === 建筑线 ===
        GOALS.put(GoalType.BUILD_SHELTER, new Goal(
            GoalType.BUILD_SHELTER, "建造庇护所", "盖一间 7x4x7 的房子", 200
        ).requires(GoalType.GATHER_COBBLE)
         .needsItem("cobblestone", 100));

        GOALS.put(GoalType.BUILD_FARM, new Goal(
            GoalType.BUILD_FARM, "建造农场", "建一个 9x9 的农田", 210
        ).requires(GoalType.BUILD_SHELTER)
         .needsItem("oak_planks", 81));

        GOALS.put(GoalType.DECORATE_HOME, new Goal(
            GoalType.DECORATE_HOME, "装饰家园", "添加床、箱子、画、花盆", 220
        ).requires(GoalType.BUILD_SHELTER));

        // === 生活线 ===
        GOALS.put(GoalType.HUNT_FOOD, new Goal(
            GoalType.HUNT_FOOD, "狩猎食物", "收集 16 个生肉", 300
        ).target("beef", 16));

        GOALS.put(GoalType.COOK_FOOD, new Goal(
            GoalType.COOK_FOOD, "烹饪食物", "烧制 16 个熟肉", 310
        ).requires(GoalType.HUNT_FOOD, GoalType.CRAFT_STONE_TOOLS)
         .needsItem("beef", 16)
         .needsItem("coal", 2)
         .target("cooked_beef", 16));

        GOALS.put(GoalType.PLANT_CROPS, new Goal(
            GoalType.PLANT_CROPS, "种植作物", "种下小麦种子", 320
        ).requires(GoalType.BUILD_FARM));

        GOALS.put(GoalType.HARVEST_CROPS, new Goal(
            GoalType.HARVEST_CROPS, "收割作物", "收获成熟的小麦", 330
        ).requires(GoalType.PLANT_CROPS));

        // === 探索线 ===
        GOALS.put(GoalType.EXPLORE_AREA, new Goal(
            GoalType.EXPLORE_AREA, "探索区域", "螺旋式搜索附近 256 格", 400
        ).requires(GoalType.BUILD_SHELTER));

        GOALS.put(GoalType.FIND_VILLAGE, new Goal(
            GoalType.FIND_VILLAGE, "寻找村庄", "找到村民并标记村庄位置", 410
        ).requires(GoalType.EXPLORE_AREA));
    }

    public static Goal get(GoalType type) {
        return GOALS.get(type);
    }

    public static List<Goal> getSurvivalPath() {
        return Arrays.asList(
            get(GoalType.GATHER_WOOD),
            get(GoalType.CRAFT_WOODEN_TOOLS),
            get(GoalType.GATHER_COBBLE),
            get(GoalType.CRAFT_STONE_TOOLS),
            get(GoalType.GATHER_COAL),
            get(GoalType.GATHER_IRON_ORE),
            get(GoalType.SMELT_IRON),
            get(GoalType.CRAFT_IRON_GEAR),
            get(GoalType.GATHER_DIAMOND),
            get(GoalType.CRAFT_DIAMOND_TOOLS)
        );
    }

    public static List<Goal> getAllGoals() {
        return new ArrayList<>(GOALS.values());
    }
}
