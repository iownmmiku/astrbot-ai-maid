package com.iownmmiku.maid;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
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
        // 找附近的树（原木方块）
        BlockPos tree = findNearbyBlock(maid, Blocks.OAK_LOG, 32.0);
        if (tree == null) {
            // 走远点找
            progress.putIfAbsent("search_radius", 32);
            int r = progress.get("search_radius");
            if (r < 128) {
                progress.put("search_radius", r + 16);
            }
            return "找树中（半径 " + r + " 格）";
        }
        // 走过去挖
        double dist = maid.getPos().distanceTo(tree.toCenterPos());
        if (dist > 5.0) {
            MaidBrain.gotoTo(maid, tree.getX() + 0.5, tree.getZ() + 0.5);
            return "前往树 (" + tree.getX() + ", " + tree.getZ() + ")";
        }
        MaidBrain.stop(maid);
        MaidActions.mine(maid, tree);
        return "采集木头 " + have + "/16";
    }

    private static String craftWoodenTools(ServerPlayerEntity maid, Map<String, Integer> progress) {
        if (MaidActions.countItem(maid, "wooden_pickaxe") > 0 && 
            MaidActions.countItem(maid, "wooden_axe") > 0) {
            return null;
        }
        // 先合成木板
        if (MaidActions.countItem(maid, "oak_planks") < 10) {
            MaidCrafting.craft(maid, "oak_planks");
        }
        // 合成木镐
        if (MaidActions.countItem(maid, "wooden_pickaxe") == 0) {
            String err = MaidCrafting.craft(maid, "wooden_pickaxe");
            if (err != null) {
                return "合成木镐失败：" + err;
            }
        }
        // 合成木斧
        if (MaidActions.countItem(maid, "wooden_axe") == 0) {
            String err = MaidCrafting.craft(maid, "wooden_axe");
            if (err != null) {
                return "合成木斧失败：" + err;
            }
        }
        return null;
    }

    private static String gatherCobble(ServerPlayerEntity maid, Map<String, Integer> progress) {
        int have = MaidActions.countItem(maid, "cobblestone");
        if (have >= 64) {
            return null;
        }
        // 找石头（向下挖或找附近石头）
        BlockPos stone = findNearbyBlock(maid, Blocks.STONE, 16.0);
        if (stone == null) {
            stone = maid.getBlockPos().down();
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
        String[] items = {"stone_pickaxe", "stone_axe", "stone_sword", "furnace", "crafting_table"};
        for (String item : items) {
            if (MaidActions.countItem(maid, item) == 0) {
                String err = MaidCrafting.craft(maid, item);
                if (err != null) {
                    return "合成 " + item + " 失败：" + err;
                }
                return "合成 " + item;
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
        String[] items = {"iron_pickaxe", "iron_sword", "iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots"};
        for (String item : items) {
            if (MaidActions.countItem(maid, item) == 0) {
                String err = MaidCrafting.craft(maid, item);
                if (err != null) {
                    return "合成 " + item + " 失败：" + err;
                }
                return "合成 " + item;
            }
        }
        return null;
    }

    private static String craftDiamondTools(ServerPlayerEntity maid, Map<String, Integer> progress) {
        if (MaidActions.countItem(maid, "diamond_pickaxe") == 0) {
            String err = MaidCrafting.craft(maid, "diamond_pickaxe");
            if (err != null) return "合成钻石镐失败：" + err;
            return "合成钻石镐";
        }
        if (MaidActions.countItem(maid, "diamond_sword") == 0) {
            String err = MaidCrafting.craft(maid, "diamond_sword");
            if (err != null) return "合成钻石剑失败：" + err;
            return "合成钻石剑";
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
        MaidActions.lookAt(maid, target.getPos());
        maid.attack(target);
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
