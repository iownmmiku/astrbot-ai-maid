package com.iownmmiku.maid;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 女仆的对外 API：指令和桥都走这里。
 * 坐标一律用**绝对坐标**（跟 AstrBot 那边的 mc_* 工具保持一致）。
 */
public final class MaidApi {
    public static final String DEFAULT_NAME = "sagiri";

    private MaidApi() {
    }

    public static ServerPlayerEntity maid(MinecraftServer server) {
        ServerPlayerEntity m = Maids.get(DEFAULT_NAME);
        if (m != null) {
            return m;
        }
        return Maids.all().values().stream().findFirst().orElse(null);
    }

    /** @return null 表示成功，否则是错误信息 */
    public static String dispatch(MinecraftServer server, String cmd, JsonObject req, JsonObject out) {
        ServerPlayerEntity m = maid(server);
        switch (cmd) {
            case "spawn": {
                // 不给坐标就用世界出生点，并自动找一块能站的地面
                net.minecraft.util.math.BlockPos sp = server.getOverworld().getSpawnPos();
                double x = opt(req, "x", sp.getX() + 0.5);
                double y = opt(req, "y", sp.getY() + 1);
                double z = opt(req, "z", sp.getZ() + 0.5);
                net.minecraft.util.math.BlockPos stand = Pathfinder.standableNear(
                        server.getOverworld(), (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
                if (stand != null) {
                    y = stand.getY();
                }
                Maids.spawn(server, DEFAULT_NAME, x, y, z);
                return null;
            }
            case "remove":
                return Maids.remove(server, DEFAULT_NAME) ? null : "no maid";
            case "status": {
                if (m == null) {
                    out.addProperty("online", false);
                    return null;
                }
                out.add("maid", status(m));
                JsonArray pl = new JsonArray();
                for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                    pl.add(p.getGameProfile().getName());
                }
                out.add("players", pl);
                return null;
            }
            case "say": {
                if (m == null) {
                    return "no maid";
                }
                String text = req.has("text") ? req.get("text").getAsString() : "";
                server.getPlayerManager().broadcast(Text.literal("<" + m.getGameProfile().getName() + "> " + text), false);
                return null;
            }
            case "gamemode": {
                if (m == null) {
                    return "no maid";
                }
                String mode = req.has("mode") ? req.get("mode").getAsString() : "survival";
                GameMode gm = GameMode.byName(mode, GameMode.SURVIVAL);
                m.changeGameMode(gm);
                return null;
            }
            case "set_goal": {
                String goalName = req.has("goal") ? req.get("goal").getAsString() : "";
                try {
                    MaidGoals.GoalType type = MaidGoals.GoalType.valueOf(goalName.toUpperCase());
                    GoalScheduler.setGoal(m, type);
                    MaidGoals.Goal goal = MaidGoals.get(type);
                    out.addProperty("goal", goal.name);
                    return null;
                } catch (IllegalArgumentException e) {
                    return "未知目标：" + goalName;
                }
            }
            case "get_goals": {
                MaidGoals.Goal current = GoalScheduler.getCurrentGoal(m);
                if (current != null) {
                    out.addProperty("current_goal", current.name);
                    out.addProperty("current_description", current.description);
                }
                Set<MaidGoals.GoalType> completed = GoalScheduler.getCompletedGoals(m);
                com.google.gson.JsonArray completedArr = new com.google.gson.JsonArray();
                for (MaidGoals.GoalType t : completed) {
                    completedArr.add(MaidGoals.get(t).name);
                }
                out.add("completed", completedArr);
                
                com.google.gson.JsonArray survivalPath = new com.google.gson.JsonArray();
                for (MaidGoals.Goal g : MaidGoals.getSurvivalPath()) {
                    com.google.gson.JsonObject gObj = new com.google.gson.JsonObject();
                    gObj.addProperty("name", g.name);
                    gObj.addProperty("completed", completed.contains(g.type));
                    survivalPath.add(gObj);
                }
                out.add("survival_path", survivalPath);
                return null;
            }
            default:
                break;
        }

        if (m == null) {
            return "no maid（先 spawn）";
        }
        switch (cmd) {
            case "stop":
                MaidBrain.stop(m);
                return null;
            case "jump":
                MaidBrain.jumpOnce(m);
                return null;
            case "walk": {
                double x = opt(req, "x", 0);
                double z = opt(req, "z", 0);
                MaidBrain.walkTo(m, x, z);
                return null;
            }
            case "goto": {
                double x = opt(req, "x", 0);
                double z = opt(req, "z", 0);
                int n = MaidBrain.gotoTo(m, x, z);
                out.addProperty("path_nodes", n);
                return null;
            }
            case "mine": {
                BlockPos pos = pos(req);
                // 方块名要在挖之前记，挖完就只剩空气了
                String blockName = net.minecraft.registry.Registries.BLOCK
                        .getId(m.getServerWorld().getBlockState(pos).getBlock()).toString();
                String err = MaidActions.mine(m, pos);
                if (err == null) {
                    MaidEvents.mined(m, pos, blockName);
                }
                return err;
            }
            case "place": {
                BlockPos pos = pos(req);
                String err = MaidActions.place(m, pos);
                if (err == null) {
                    MaidEvents.placed(m, pos);
                }
                return err;
            }
            case "give": {
                String item = req.has("item") ? req.get("item").getAsString() : "";
                int count = req.has("count") ? req.get("count").getAsInt() : 64;
                return MaidActions.give(m, item, count);
            }
            case "hold": {
                String item = req.has("item") ? req.get("item").getAsString() : "";
                return MaidActions.hold(m, item);
            }
            case "inv": {
                JsonArray arr = new JsonArray();
                for (String s : MaidActions.inventory(m)) {
                    arr.add(s);
                }
                out.add("inventory", arr);
                return null;
            }
            case "health": {
                m.setHealth(20.0F);
                m.getHungerManager().setFoodLevel(20);
                return null;
            }
            case "build": {
                if (!req.has("plan")) {
                    return "缺少 plan 参数";
                }
                com.google.gson.JsonArray arr = req.getAsJsonArray("plan");
                List<BuildTask.Placement> rawPlan = new ArrayList<>();
                for (int i = 0; i < arr.size(); i++) {
                    com.google.gson.JsonObject p = arr.get(i).getAsJsonObject();
                    int x = p.get("x").getAsInt();
                    int y = p.get("y").getAsInt();
                    int z = p.get("z").getAsInt();
                    String block = p.get("block").getAsString();
                    rawPlan.add(new BuildTask.Placement(new BlockPos(x, y, z), block));
                }
                // 检查材料
                String errMat = BuildTask.checkMaterials(m, rawPlan);
                if (errMat != null) {
                    return errMat;
                }
                // 排序（从下到上、从近到远）
                List<BuildTask.Placement> sorted = BuildTask.sortPlan(m, rawPlan);
                BuildTask task = new BuildTask(sorted);
                MaidBrain.startBuild(m, task);
                out.addProperty("blocks", sorted.size());
                AiMaidMod.LOGGER.info("[AI-Maid] {} starting build: {} blocks", 
                        m.getGameProfile().getName(), sorted.size());
                return null;
            }
            default:
                return "未知指令：" + cmd;
        }
    }

    public static JsonObject status(ServerPlayerEntity m) {
        JsonObject o = new JsonObject();
        o.addProperty("name", m.getGameProfile().getName());
        o.addProperty("x", round(m.getX()));
        o.addProperty("y", round(m.getY()));
        o.addProperty("z", round(m.getZ()));
        o.addProperty("yaw", round(m.getYaw()));
        o.addProperty("health", m.getHealth());
        o.addProperty("food", m.getHungerManager().getFoodLevel());
        o.addProperty("on_ground", m.isOnGround());
        o.addProperty("game_mode", m.interactionManager.getGameMode().getName());
        o.addProperty("task", MaidBrain.describe(m));
        
        // 目标系统状态
        MaidGoals.Goal currentGoal = GoalScheduler.getCurrentGoal(m);
        if (currentGoal != null) {
            o.addProperty("current_goal", currentGoal.name);
        }
        Set<MaidGoals.GoalType> completed = GoalScheduler.getCompletedGoals(m);
        int survivalProgress = (int) completed.stream()
                .filter(g -> MaidGoals.getSurvivalPath().stream().anyMatch(sg -> sg.type == g))
                .count();
        o.addProperty("survival_progress", survivalProgress + "/" + MaidGoals.getSurvivalPath().size());
        
        JsonArray inv = new JsonArray();
        List<String> items = MaidActions.inventory(m);
        for (String s : items) {
            inv.add(s);
        }
        o.add("inventory", inv);
        ItemStack hand = m.getMainHandStack();
        o.addProperty("hand", hand.isEmpty() ? ""
                : Registries.ITEM.getId(hand.getItem()).toString() + " x" + hand.getCount());
        JsonArray players = new JsonArray();
        for (ServerPlayerEntity p : m.getServer().getPlayerManager().getPlayerList()) {
            players.add(p.getGameProfile().getName());
        }
        o.add("players", players);
        return o;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double opt(JsonObject o, String k, double def) {
        return o.has(k) ? o.get(k).getAsDouble() : def;
    }

    private static BlockPos pos(JsonObject o) {
        return new BlockPos((int) Math.floor(opt(o, "x", 0)),
                (int) Math.floor(opt(o, "y", 0)),
                (int) Math.floor(opt(o, "z", 0)));
    }
}
