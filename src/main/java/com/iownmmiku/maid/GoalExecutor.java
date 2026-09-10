package com.iownmmiku.maid;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.*;

/**
 * 目标执行器：把抽象目标翻译成具体动作。
 */
public final class GoalExecutor {
    private GoalExecutor() {
    }

    /**
     * 执行一个目标的一步（非阻塞）。
     * @return null 表示目标完成，否则返回进度描述
     */
    public static String executeStep(ServerPlayerEntity maid, MaidGoals.Goal goal, Map<String, Integer> progress) {
        // 通用前置：优先拾取附近掉落物（挖的树/矿石掉地上要捡起来才有用）
        String pickup = pickupDrops(maid, progress);
        if (pickup != null) {
            return pickup;
        }
        switch (goal.type) {
            case GATHER_WOOD:
                return gatherWood(maid, progress);
            case CRAFT_WOODEN_TOOLS:
                return craftWoodenTools(maid, progress);
            case GATHER_COBBLE:
                return gatherCobble(maid, progress);
            case CRAFT_STONE_TOOLS:
                return craftStoneTools(maid, progress);
            case GATHER_COAL:
                return gatherCoal(maid, progress);
            case GATHER_IRON_ORE:
                return gatherIronOre(maid, progress);
            case SMELT_IRON:
                return smeltIron(maid, progress);
            case CRAFT_IRON_GEAR:
                return craftIronGear(maid, progress);
            case GATHER_DIAMOND:
                return gatherDiamond(maid, progress);
            case CRAFT_DIAMOND_TOOLS:
                return craftDiamondTools(maid, progress);
            case BUILD_SHELTER:
                return buildShelter(maid, progress);
            case BUILD_FARM:
                return buildFarm(maid, progress);
            case HUNT_FOOD:
                return huntFood(maid, progress);
            case COOK_FOOD:
                return cookFood(maid, progress);
            case PLANT_CROPS:
                return plantCrops(maid, progress);
            case HARVEST_CROPS:
                return harvestCrops(maid, progress);
            case EXPLORE_AREA:
                return exploreArea(maid, progress);
            case FIND_VILLAGE:
                return findVillage(maid, progress);
            default:
                return null;
        }
    }

    private static String gatherWood(ServerPlayerEntity maid, Map<String, Integer> progress) {
        int have = MaidActions.countItem(maid, "oak_log");
        if (have >= 16) {
            return null;  // 完成
        }

        // 记录搜索起点（家/出生点）
        progress.putIfAbsent("home_x", maid.getBlockX());
        progress.putIfAbsent("home_z", maid.getBlockZ());

        // 世界类型检测：超平坦/无树世界，直接明确报错，别白转圈
        if (!progress.containsKey("world_checked")) {
            progress.put("world_checked", 1);
            // 从脚下一路往上找有没有天然方块（超平坦世界 Y 都很低且无树）
            if (maid.getServerWorld().getTopY(
                    net.minecraft.world.Heightmap.Type.WORLD_SURFACE,
                    maid.getBlockX(), maid.getBlockZ()) < 0) {
                AiMaidMod.LOGGER.warn("[AI-Maid] 当前世界疑似超平坦（地表 Y<0），没有树/石头/矿石，" +
                        "生存线无法完成。请改用普通世界（level-type=normal）。");
            }
        }

        // 找附近的树（原木方块）
        BlockPos tree = findNearbyBlock(maid, Blocks.OAK_LOG, 24.0);
        if (tree == null) {
            // 找不到树 → 环形主动搜索：向 8 个方向依次走 16~80 格，边走边找
            int homeX = progress.get("home_x");
            int homeZ = progress.get("home_z");
            int ring = progress.getOrDefault("search_ring", 0);
            if (ring > 40) {
                // 40 站都没找到（=绕了 5 圈）→ 换起点重新搜
                progress.put("search_ring", 0);
                progress.put("home_x", maid.getBlockX());
                progress.put("home_z", maid.getBlockZ());
                return "这片区域没有树，换个地方找";
            }
            int dir = ring % 8;
            int lap = ring / 8 + 1;
            double ang = dir * Math.PI / 4.0;
            int tx = homeX + (int) (Math.cos(ang) * 16 * lap);
            int tz = homeZ + (int) (Math.sin(ang) * 16 * lap);
            double dist = Math.hypot(tx - maid.getX(), tz - maid.getZ());
            if (dist > 4.0) {
                MaidBrain.gotoTo(maid, tx, tz);
                return "搜索树木中（第 " + (ring + 1) + " 站，方向 " + dir + "，半径 " + 16 * lap + "）";
            }
            // 到达搜索点但没树 → 下一站
            progress.put("search_ring", ring + 1);
            int sweeps = progress.getOrDefault("search_sweeps", 0);
            if (ring + 1 >= 24) {
                // 整整 3 圈都没树 → 这片区域真的没有树，别无限绕
                progress.put("search_sweeps", sweeps + 1);
                progress.put("search_ring", 0);
                progress.put("home_x", maid.getBlockX());
                progress.put("home_z", maid.getBlockZ());
                if (sweeps + 1 >= 2) {
                    AiMaidMod.LOGGER.warn("[AI-Maid] {} 连续 2 次大范围搜索都没找到树，" +
                            "可能是超平坦/沙漠/海洋世界。建议改为普通世界。", maid.getGameProfile().getName());
                    GoalScheduler.markBlocked(maid, MaidGoals.GoalType.GATHER_WOOD);
                    return null;
                }
                return "这片区域没树，换个地方找";
            }
            return "到达搜索点，继续找树";
        }

        // 找到树 → 走过去挖（重置搜索状态）
        progress.put("search_ring", 0);
        double dist = maid.getPos().distanceTo(tree.toCenterPos());
        if (dist > 4.5) {
            MaidBrain.gotoTo(maid, tree.getX() + 0.5, tree.getZ() + 0.5);
            return "前往树 (" + tree.getX() + ", " + tree.getZ() + ")";
        }
        MaidBrain.stop(maid);
        // 有斧头用斧头，挖得快
        if (MaidActions.countItem(maid, "wooden_axe") > 0) {
            MaidActions.hold(maid, "wooden_axe");
        }
        String err = MaidActions.mine(maid, tree);
        if (err != null) {
            return "挖树失败：" + err;
        }
        return "采集木头 " + have + "/16";
    }

    private static String craftWoodenTools(ServerPlayerEntity maid, Map<String, Integer> progress) {
        if (MaidActions.countItem(maid, "wooden_pickaxe") > 0 && 
            MaidActions.countItem(maid, "wooden_axe") > 0) {
            return null;
        }
        // 一步一动作：每次 tick 只做一件事，防止疯狂合成
        // 先确保有工作台（木镐/斧需要）
        if (MaidActions.countItem(maid, "crafting_table") == 0
                && findNearbyBlock(maid, Blocks.CRAFTING_TABLE, 8.0) == null) {
            if (MaidActions.countItem(maid, "oak_planks") < 4) {
                String err = MaidCrafting.craft(maid, "oak_planks");
                return err != null ? "合成木板失败：" + err : "合成木板（准备做工作台）";
            }
            String err = MaidCrafting.craft(maid, "crafting_table");
            return err != null ? "合成工作台失败：" + err : "合成工作台";
        }
        if (MaidActions.countItem(maid, "oak_planks") < 10) {
            String err = MaidCrafting.craft(maid, "oak_planks");
            return err != null ? "合成木板失败：" + err : "合成木板";
        }
        if (MaidActions.countItem(maid, "wooden_pickaxe") == 0) {
            String err = MaidCrafting.craft(maid, "wooden_pickaxe");
            return err != null ? "合成木镐失败：" + err : "合成木镐";
        }
        if (MaidActions.countItem(maid, "wooden_axe") == 0) {
            String err = MaidCrafting.craft(maid, "wooden_axe");
            return err != null ? "合成木斧失败：" + err : "合成木斧";
        }
        return null;
    }

    private static String gatherCobble(ServerPlayerEntity maid, Map<String, Integer> progress) {
        int have = MaidActions.countItem(maid, "cobblestone");
        if (have >= 64) {
            return null;
        }
        // 找附近的石头；找不到就向下挖（地下总是有石头）
        BlockPos stone = findNearbyBlock(maid, Blocks.STONE, 24.0);
        if (stone == null) {
            BlockPos down = maid.getBlockPos().down();
            net.minecraft.block.Block below = maid.getServerWorld().getBlockState(down).getBlock();
            if (below == Blocks.DIRT || below == Blocks.GRASS_BLOCK || below == Blocks.STONE
                    || below == Blocks.DEEPSLATE) {
                stone = down;
            } else {
                // 原地挖脚下
                stone = maid.getBlockPos();
            }
        }
        double dist = maid.getPos().distanceTo(stone.toCenterPos());
        if (dist > 5.0) {
            MaidBrain.gotoTo(maid, stone.getX() + 0.5, stone.getZ() + 0.5);
            return "前往石头";
        }
        MaidBrain.stop(maid);
        MaidActions.hold(maid, "wooden_pickaxe");
        MaidActions.mine(maid, stone);
        return "挖石头 " + have + "/64";
    }

    private static String craftStoneTools(ServerPlayerEntity maid, Map<String, Integer> progress) {
        // 一步一动作：每次 tick 只合成一个物品
        String[] items = {"stone_pickaxe", "stone_axe", "stone_sword", "furnace", "crafting_table"};
        for (String item : items) {
            if (MaidActions.countItem(maid, item) == 0) {
                String err = MaidCrafting.craft(maid, item);
                return err != null ? "合成 " + item + " 失败：" + err : "合成 " + item;
            }
        }
        return null;
    }

    private static String gatherCoal(ServerPlayerEntity maid, Map<String, Integer> progress) {
        int have = MaidActions.countItem(maid, "coal");
        if (have >= 32) {
            return null;
        }
        return gatherOre(maid, "coal_ore", "coal", 32, "stone_pickaxe", progress);
    }

    private static String gatherIronOre(ServerPlayerEntity maid, Map<String, Integer> progress) {
        int have = MaidActions.countItem(maid, "raw_iron");
        if (have >= 24) {
            return null;
        }
        return gatherOre(maid, "iron_ore", "raw_iron", 24, "stone_pickaxe", progress);
    }

    private static String gatherDiamond(ServerPlayerEntity maid, Map<String, Integer> progress) {
        int have = MaidActions.countItem(maid, "diamond");
        if (have >= 3) {
            return null;
        }
        return gatherOre(maid, "diamond_ore", "diamond", 3, "iron_pickaxe", progress);
    }

    private static String gatherOre(ServerPlayerEntity maid, String oreBlock, String item, int target, String tool, Map<String, Integer> progress) {
        int have = MaidActions.countItem(maid, item);
        if (have >= target) {
            return null;
        }
        // 使用智能挖矿系统
        String result = MaidMining.mineOre(maid, oreBlock, tool, progress);
        if (result != null) {
            return result;
        }
        return "挖矿 " + item + " " + have + "/" + target;
    }

    private static String smeltIron(ServerPlayerEntity maid, Map<String, Integer> progress) {
        int have = MaidActions.countItem(maid, "iron_ingot");
        if (have >= 24) {
            return null;
        }
        int raw = MaidActions.countItem(maid, "raw_iron");
        if (raw > 0) {
            int toSmelt = Math.min(raw, 24 - have);
            String err = MaidCrafting.smelt(maid, "raw_iron", toSmelt);
            if (err != null) {
                return "烧铁失败：" + err;
            }
            return "烧铁 " + (have + toSmelt) + "/24";
        }
        return null;
    }

    private static String craftIronGear(ServerPlayerEntity maid, Map<String, Integer> progress) {
        // 一步一动作
        String[] items = {"iron_pickaxe", "iron_sword", "iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots"};
        for (String item : items) {
            if (MaidActions.countItem(maid, item) == 0) {
                String err = MaidCrafting.craft(maid, item);
                return err != null ? "合成 " + item + " 失败：" + err : "合成 " + item;
            }
        }
        return null;
    }

    private static String craftDiamondTools(ServerPlayerEntity maid, Map<String, Integer> progress) {
        // 一步一动作
        if (MaidActions.countItem(maid, "diamond_pickaxe") == 0) {
            String err = MaidCrafting.craft(maid, "diamond_pickaxe");
            return err != null ? "合成钻石镐失败：" + err : "合成钻石镐";
        }
        if (MaidActions.countItem(maid, "diamond_sword") == 0) {
            String err = MaidCrafting.craft(maid, "diamond_sword");
            return err != null ? "合成钻石剑失败：" + err : "合成钻石剑";
        }
        return null;
    }

    private static String buildShelter(ServerPlayerEntity maid, Map<String, Integer> progress) {
        // 委托给建筑系统（已有的 BuildTask）
        BuildTask task = MaidBrain.getBuildTask(maid);
        if (task != null && !task.isDone()) {
            return "建造中 " + task.progress() + "/" + task.total();
        }
        if (task != null && task.isDone()) {
            return null;
        }
        // 生成 7x4x7 房子蓝图
        List<BuildTask.Placement> plan = generateShelterBlueprint(maid);
        String err = BuildTask.checkMaterials(maid, plan);
        if (err != null) {
            return "材料不足：" + err;
        }
        plan = BuildTask.sortPlan(maid, plan);
        MaidBrain.startBuild(maid, new BuildTask(plan));
        return "开始建造庇护所";
    }

    private static String buildFarm(ServerPlayerEntity maid, Map<String, Integer> progress) {
        // 9x9 平地农田
        BuildTask task = MaidBrain.getBuildTask(maid);
        if (task != null && !task.isDone()) {
            return "建造中 " + task.progress() + "/" + task.total();
        }
        if (task != null && task.isDone()) {
            return null;
        }
        List<BuildTask.Placement> plan = generateFarmBlueprint(maid);
        plan = BuildTask.sortPlan(maid, plan);
        MaidBrain.startBuild(maid, new BuildTask(plan));
        return "开始建造农场";
    }

    private static String huntFood(ServerPlayerEntity maid, Map<String, Integer> progress) {
        int have = MaidActions.countItem(maid, "beef") + 
                   MaidActions.countItem(maid, "porkchop") + 
                   MaidActions.countItem(maid, "chicken");
        if (have >= 16) {
            return null;
        }
        // 找附近的动物
        ServerWorld world = maid.getServerWorld();
        Box box = maid.getBoundingBox().expand(16.0);
        List<AnimalEntity> animals = world.getEntitiesByClass(AnimalEntity.class, box, 
                e -> e.isAlive() && !e.isBaby());
        if (animals.isEmpty()) {
            return "寻找动物中";
        }
        AnimalEntity target = animals.get(0);
        double dist = maid.distanceTo(target);
        if (dist > 3.0) {
            MaidBrain.gotoTo(maid, target.getX(), target.getZ());
            return "追逐动物";
        }
        MaidActions.attack(maid, target);
        return "狩猎 " + have + "/16";
    }

    private static String cookFood(ServerPlayerEntity maid, Map<String, Integer> progress) {
        int cooked = MaidActions.countItem(maid, "cooked_beef") + 
                     MaidActions.countItem(maid, "cooked_porkchop") + 
                     MaidActions.countItem(maid, "cooked_chicken");
        if (cooked >= 16) {
            return null;
        }
        // 烧肉
        int beef = MaidActions.countItem(maid, "beef");
        if (beef > 0) {
            int toSmelt = Math.min(beef, 16 - cooked);
            String err = MaidCrafting.smelt(maid, "beef", toSmelt);
            if (err != null) return "烹饪失败：" + err;
            return "烹饪 " + (cooked + toSmelt) + "/16";
        }
        return null;
    }

    private static String plantCrops(ServerPlayerEntity maid, Map<String, Integer> progress) {
        // 委托给种田系统
        return MaidFarming.plantCrops(maid, progress);
    }

    private static String harvestCrops(ServerPlayerEntity maid, Map<String, Integer> progress) {
        return MaidFarming.harvestCrops(maid, progress);
    }

    private static String exploreArea(ServerPlayerEntity maid, Map<String, Integer> progress) {
        return MaidExploration.spiralSearch(maid, progress);
    }

    private static String findVillage(ServerPlayerEntity maid, Map<String, Integer> progress) {
        return MaidExploration.findVillage(maid, progress);
    }

    // === 辅助方法 ===
    /** 拾取附近 6 格内的掉落物。没有掉落物返回 null；超过 5 秒捡不起来就放弃。 */
    private static String pickupDrops(ServerPlayerEntity maid, Map<String, Integer> progress) {
        int pt = progress.getOrDefault("pickup_ticks", 0);
        ServerWorld world = maid.getServerWorld();
        Box box = maid.getBoundingBox().expand(6.0);
        List<ItemEntity> items = world.getEntitiesByClass(ItemEntity.class, box, e -> e.isAlive());
        if (items.isEmpty()) {
            progress.put("pickup_ticks", 0);
            return null;
        }
        if (pt > 100) {
            // 5 秒捡不起来（可能掉进岩浆/卡在缝隙）→ 放弃，去做正事
            progress.put("pickup_ticks", 0);
            return null;
        }
        progress.put("pickup_ticks", pt + 1);
        items.sort((a, b) -> Double.compare(a.squaredDistanceTo(maid), b.squaredDistanceTo(maid)));
        ItemEntity item = items.get(0);
        MaidBrain.gotoTo(maid, item.getX(), item.getZ());
        return "拾取掉落物";
    }

    private static BlockPos findNearbyBlock(ServerPlayerEntity maid, Block block, double range) {
        ServerWorld world = maid.getServerWorld();
        BlockPos center = maid.getBlockPos();
        int r = (int) range;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -8; dy <= 8; dy++) {
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

    private static List<BuildTask.Placement> generateShelterBlueprint(ServerPlayerEntity maid) {
        List<BuildTask.Placement> plan = new ArrayList<>();
        BlockPos origin = maid.getBlockPos().add(5, 0, 5);
        // 7x4x7 房子：地基 + 4 面墙 + 屋顶
        // 简化版：只铺地板和墙
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                plan.add(new BuildTask.Placement(origin.add(x, 0, z), "minecraft:oak_planks"));
                if (x == 0 || x == 6 || z == 0 || z == 6) {
                    for (int y = 1; y <= 3; y++) {
                        plan.add(new BuildTask.Placement(origin.add(x, y, z), "minecraft:oak_planks"));
                    }
                }
            }
        }
        return plan;
    }

    private static List<BuildTask.Placement> generateFarmBlueprint(ServerPlayerEntity maid) {
        List<BuildTask.Placement> plan = new ArrayList<>();
        BlockPos origin = maid.getBlockPos().add(10, 0, 10);
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                plan.add(new BuildTask.Placement(origin.add(x, 0, z), "minecraft:farmland"));
            }
        }
        return plan;
    }
}
