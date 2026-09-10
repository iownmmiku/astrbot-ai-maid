package com.iownmmiku.maid;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;

/** 女仆主动上报的"感觉"：挖到了、挨打了、饿了、到了。 */
public final class MaidEvents {
    private static final Map<String, Float> LAST_HEALTH = new HashMap<>();
    private static final Map<String, Integer> LAST_FOOD = new HashMap<>();

    private MaidEvents() {
    }

    private static String key(ServerPlayerEntity m) {
        return m.getGameProfile().getName().toLowerCase();
    }

    public static void arrived(ServerPlayerEntity m, double x, double y, double z) {
        JsonObject o = new JsonObject();
        o.addProperty("event", "arrived");
        o.addProperty("who", key(m));
        o.addProperty("x", Math.round(x * 100) / 100.0);
        o.addProperty("y", Math.round(y * 100) / 100.0);
        o.addProperty("z", Math.round(z * 100) / 100.0);
        BridgeServer.emit(o);
    }

    public static void mined(ServerPlayerEntity m, BlockPos pos, String blockName) {
        JsonObject o = new JsonObject();
        o.addProperty("event", "mined");
        o.addProperty("who", key(m));
        o.addProperty("block", blockName);
        o.addProperty("x", pos.getX());
        o.addProperty("y", pos.getY());
        o.addProperty("z", pos.getZ());
        BridgeServer.emit(o);
    }

    public static void placed(ServerPlayerEntity m, BlockPos pos) {
        JsonObject o = new JsonObject();
        o.addProperty("event", "placed");
        o.addProperty("who", key(m));
        o.addProperty("x", pos.getX());
        o.addProperty("y", pos.getY());
        o.addProperty("z", pos.getZ());
        BridgeServer.emit(o);
    }

    /** 每 tick 检查血量/饥饿的变化。 */
    public static void tick(MinecraftServer server) {
        for (ServerPlayerEntity m : Maids.all().values()) {
            String k = key(m);
            float hp = m.getHealth();
            Float prev = LAST_HEALTH.get(k);
            if (prev != null && Math.abs(prev - hp) > 0.01F) {
                JsonObject o = new JsonObject();
                o.addProperty("event", hp < prev ? "hurt" : "heal");
                o.addProperty("who", k);
                o.addProperty("health", hp);
                o.addProperty("delta", Math.round((hp - prev) * 100) / 100.0);
                BridgeServer.emit(o);
            }
            LAST_HEALTH.put(k, hp);

            int food = m.getHungerManager().getFoodLevel();
            Integer pf = LAST_FOOD.get(k);
            if (pf != null && pf - food >= 1) {
                JsonObject o = new JsonObject();
                o.addProperty("event", "food");
                o.addProperty("who", k);
                o.addProperty("food", food);
                BridgeServer.emit(o);
            }
            LAST_FOOD.put(k, food);
        }
    }
}
