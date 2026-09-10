package com.iownmmiku.maid;

import com.sun.net.httpserver.HttpServer;
import net.fabricmc.loader.api.FabricLoader;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/** 把 config/astrbot_ai_maid/skin.png 用本地 HTTP 发出去，客户端自己去拉。 */
public final class SkinServer {
    private static int port = 8123;
    private static HttpServer http;

    public static Path skinFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("astrbot_ai_maid").resolve("skin.png");
    }

    public static synchronized void start(int p) {
        port = p;
        if (http != null) {
            return;
        }
        try {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            http.createContext("/skin.png", exchange -> {
                AiMaidMod.LOGGER.info("[AI-Maid] skin request from {}", exchange.getRemoteAddress());
                Path f = skinFile();
                if (!Files.exists(f)) {
                    byte[] msg = "no skin.png".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(404, msg.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(msg);
                    }
                    return;
                }
                byte[] data = Files.readAllBytes(f);
                exchange.getResponseHeaders().add("Content-Type", "image/png");
                exchange.sendResponseHeaders(200, data.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(data);
                }
            });
            http.start();
            AiMaidMod.LOGGER.info("[AI-Maid] skin server -> http://127.0.0.1:{}/skin.png", port);
        } catch (Exception e) {
            AiMaidMod.LOGGER.error("[AI-Maid] skin server 启动失败", e);
        }
    }

    /** 生成 GameProfile 里的 textures 属性（slim 模型）。 */
    public static String textureProperty() {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://127.0.0.1:" + port
                + "/skin.png\",\"metadata\":{\"model\":\"slim\"}}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
