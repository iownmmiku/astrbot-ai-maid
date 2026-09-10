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

import java.util.List;

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
