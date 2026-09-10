package com.iownmmiku.maid;

import com.google.gson.JsonObject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AiMaidMod implements ModInitializer {
    public static final String MOD_ID = "astrbot_ai_maid";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[AI-Maid] loading...");

        SkinServer.start(8123);
        BridgeServer.start();

        // 每个 tick：先执行外部下达的指令，再跑女仆自己的脑子，最后检查状态变化
        ServerTickEvents.END_SERVER_TICK.register(BridgeServer::drain);
        MaidBrain.init();
        ServerTickEvents.END_SERVER_TICK.register(MaidEvents::tick);

        // 玩家聊天 → 推给外部
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            JsonObject o = new JsonObject();
            o.addProperty("event", "chat");
            o.addProperty("name", sender.getGameProfile().getName());
            o.addProperty("text", message.getContent().getString());
            BridgeServer.emit(o);
        });

        AiMaidCommands.register();
        LOGGER.info("[AI-Maid] ready.");
    }
}
