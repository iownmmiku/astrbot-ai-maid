package com.iownmmiku.maid;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.Heightmap;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 女仆（假玩家）的生成与查找。 */
public final class Maids {
    private static final Map<String, ServerPlayerEntity> MAIDS = new LinkedHashMap<>();

    private Maids() {
    }

    public static ServerPlayerEntity spawn(MinecraftServer server, String name, double x, double y, double z) {
        ServerWorld world = server.getOverworld();
        GameProfile profile = new GameProfile(uuidFor(name), name);
        profile.getProperties().put("textures", new Property("textures", SkinServer.textureProperty()));

        // 强制放到地表（忽略传入的 y，避免生成在地下/虚空导致无法自主生存）
        int surfaceY = world.getTopY(Heightmap.Type.WORLD_SURFACE, (int) Math.floor(x), (int) Math.floor(z));
        y = surfaceY + 1.0;

        ServerPlayerEntity player = new ServerPlayerEntity(server, world, profile);
        FakeClientConnection connection = new FakeClientConnection();
        player.setPos(x, y, z);
        server.getPlayerManager().onPlayerConnect(connection, player);
        player.setPos(x, y, z);

        MAIDS.put(name.toLowerCase(), player);
        // 强制生存模式（服务器默认可能是 creative，但女仆需要真实生存）
        player.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
        AiMaidMod.LOGGER.info("[AI-Maid] spawned {} at ({}, {}, {})", name, x, y, z);
        return player;
    }

    public static ServerPlayerEntity get(String name) {
        return MAIDS.get(name.toLowerCase());
    }

    public static boolean remove(MinecraftServer server, String name) {
        ServerPlayerEntity player = MAIDS.remove(name.toLowerCase());
        if (player == null) {
            return false;
        }
        server.getPlayerManager().remove(player);
        AiMaidMod.LOGGER.info("[AI-Maid] removed {}", name);
        return true;
    }

    public static Map<String, ServerPlayerEntity> all() {
        return MAIDS;
    }

    /** 固定 UUID：客户端按 UUID 缓存皮肤，换名字才会重新拉。 */
    private static UUID uuidFor(String name) {
        return UUID.nameUUIDFromBytes(("astrbot-ai-maid:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
