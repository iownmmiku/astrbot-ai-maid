package com.iownmmiku.maid;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 给外部（AstrBot）用的桥：TCP + 一行一个 JSON，双向。
 * - 收指令：{"id":1,"cmd":"goto","x":10,"z":20}
 * - 回结果：{"id":1,"ok":true}
 * - **主动推事件**：{"event":"mined","block":"minecraft:oak_log",...}
 *
 * 只监听 127.0.0.1。所有世界操作都在服务器主线程上执行（排队后由 tick 取走）。
 */
public final class BridgeServer {
    public static final int PORT = 8124;

    private static ServerSocket socket;
    private static final Map<String, PrintWriter> CONNS = new ConcurrentHashMap<>();
    private static final Queue<String[]> PENDING = new ConcurrentLinkedQueue<>();
    private static MinecraftServerHolder holder = new MinecraftServerHolder();

    static class MinecraftServerHolder {
        net.minecraft.server.MinecraftServer server;
    }

    private BridgeServer() {
    }

    public static void start() {
        try {
            socket = new ServerSocket();
            socket.bind(new InetSocketAddress("127.0.0.1", PORT));
            Thread t = new Thread(BridgeServer::acceptLoop, "ai-maid-bridge");
            t.setDaemon(true);
            t.start();
            AiMaidMod.LOGGER.info("[AI-Maid] bridge listening on 127.0.0.1:{}", PORT);
        } catch (Exception e) {
            AiMaidMod.LOGGER.error("[AI-Maid] bridge 启动失败", e);
        }
    }

    private static void acceptLoop() {
        while (socket != null && !socket.isClosed()) {
            try {
                Socket c = socket.accept();
                final String id = "conn" + System.nanoTime();
                PrintWriter out = new PrintWriter(
                        new OutputStreamWriter(c.getOutputStream(), StandardCharsets.UTF_8), true);
                CONNS.put(id, out);
                Thread th = new Thread(() -> readLoop(id, c), "ai-maid-" + id);
                th.setDaemon(true);
                th.start();
                AiMaidMod.LOGGER.info("[AI-Maid] bridge client connected: {}", id);
            } catch (IOException e) {
                if (socket != null && !socket.isClosed()) {
                    AiMaidMod.LOGGER.warn("[AI-Maid] bridge accept 出错: {}", e.toString());
                }
            }
        }
    }

    private static void readLoop(String id, Socket c) {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (!line.isBlank()) {
                    PENDING.add(new String[]{line, id});
                }
            }
        } catch (IOException ignored) {
            // 客户端断开
        } finally {
            CONNS.remove(id);
            AiMaidMod.LOGGER.info("[AI-Maid] bridge client gone: {}", id);
        }
    }

    /** 由服务器 tick 调用：主线程上执行排队指令。 */
    public static void drain(net.minecraft.server.MinecraftServer server) {
        holder.server = server;
        String[] item;
        int n = 0;
        while ((item = PENDING.poll()) != null && n++ < 64) {
            String reply = execute(server, item[0]);
            PrintWriter w = CONNS.get(item[1]);
            if (w != null) {
                w.println(reply);
            }
        }
    }

    private static String execute(net.minecraft.server.MinecraftServer server, String line) {
        JsonObject out = new JsonObject();
        try {
            JsonObject req = JsonParser.parseString(line).getAsJsonObject();
            out.addProperty("id", req.has("id") ? req.get("id").getAsInt() : -1);
            String cmd = req.has("cmd") ? req.get("cmd").getAsString() : "";
            String err = MaidApi.dispatch(server, cmd, req, out);
            out.addProperty("ok", err == null);
            if (err != null) {
                out.addProperty("error", err);
            }
        } catch (Exception e) {
            out.addProperty("ok", false);
            out.addProperty("error", "bad request: " + e);
        }
        return out.toString();
    }

    /** 主动推送一个事件给所有连着的客户端。 */
    public static void emit(JsonObject ev) {
        String s = ev.toString();
        for (PrintWriter w : CONNS.values()) {
            w.println(s);
        }
    }
}
