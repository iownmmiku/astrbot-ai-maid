package com.iownmmiku.maid;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

/** RCON / 控制台用的指令层：只是 MaidApi 的薄壳，坐标全部是绝对坐标。 */
public final class AiMaidCommands {
    private AiMaidCommands() {
    }

    private static int run(CommandContext<ServerCommandSource> ctx, JsonObject req) {
        JsonObject out = new JsonObject();
        String err = MaidApi.dispatch(ctx.getSource().getServer(),
                req.get("cmd").getAsString(), req, out);
        String msg = err == null ? ("ok " + out) : ("失败：" + err);
        ctx.getSource().sendFeedback(() -> Text.literal(msg), false);
        return err == null ? 1 : 0;
    }

    private static JsonObject req(String cmd) {
        JsonObject o = new JsonObject();
        o.addProperty("cmd", cmd);
        return o;
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("aimaid")
                        .requires(s -> s.hasPermissionLevel(2))
                        .then(CommandManager.literal("spawn").executes(ctx -> {
                            JsonObject r = req("spawn");
                            r.addProperty("x", ctx.getSource().getPosition().x);
                            r.addProperty("y", ctx.getSource().getPosition().y);
                            r.addProperty("z", ctx.getSource().getPosition().z);
                            return run(ctx, r);
                        }))
                        .then(CommandManager.literal("status")
                                .executes(ctx -> run(ctx, req("status"))))
                        .then(CommandManager.literal("stop")
                                .executes(ctx -> run(ctx, req("stop"))))
                        .then(CommandManager.literal("jump")
                                .executes(ctx -> run(ctx, req("jump"))))
                        .then(CommandManager.literal("inv")
                                .executes(ctx -> run(ctx, req("inv"))))
                        .then(CommandManager.literal("remove").executes(ctx -> {
                            JsonObject r = req("remove");
                            return run(ctx, r);
                        }))
                        .then(CommandManager.literal("walk").then(xz("walk")))
                        .then(CommandManager.literal("goto").then(xz("goto")))
                        .then(CommandManager.literal("mine").then(xyz("mine")))
                        .then(CommandManager.literal("place").then(xyz("place")))
                        .then(CommandManager.literal("give")
                                .then(CommandManager.argument("item", StringArgumentType.word())
                                        .executes(ctx -> {
                                            JsonObject r = req("give");
                                            r.addProperty("item", StringArgumentType.getString(ctx, "item"));
                                            return run(ctx, r);
                                        })
                                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 6400))
                                                .executes(ctx -> {
                                                    JsonObject r = req("give");
                                                    r.addProperty("item", StringArgumentType.getString(ctx, "item"));
                                                    r.addProperty("count", IntegerArgumentType.getInteger(ctx, "count"));
                                                    return run(ctx, r);
                                                }))))
                        .then(CommandManager.literal("hold")
                                .then(CommandManager.argument("item", StringArgumentType.word())
                                        .executes(ctx -> {
                                            JsonObject r = req("hold");
                                            r.addProperty("item", StringArgumentType.getString(ctx, "item"));
                                            return run(ctx, r);
                                        })))
                        .then(CommandManager.literal("say")
                                .then(CommandManager.argument("text", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            JsonObject r = req("say");
                                            r.addProperty("text", StringArgumentType.getString(ctx, "text"));
                                            return run(ctx, r);
                                        })))
                ));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, Double> xz(
            String cmd) {
        return CommandManager.argument("x", DoubleArgumentType.doubleArg())
                .then(CommandManager.argument("z", DoubleArgumentType.doubleArg())
                        .executes(ctx -> {
                            JsonObject r = req(cmd);
                            r.addProperty("x", DoubleArgumentType.getDouble(ctx, "x"));
                            r.addProperty("z", DoubleArgumentType.getDouble(ctx, "z"));
                            return run(ctx, r);
                        }));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, Double> xyz(
            String cmd) {
        return CommandManager.argument("x", DoubleArgumentType.doubleArg())
                .then(CommandManager.argument("y", DoubleArgumentType.doubleArg())
                        .then(CommandManager.argument("z", DoubleArgumentType.doubleArg())
                                .executes(ctx -> {
                                    JsonObject r = req(cmd);
                                    r.addProperty("x", DoubleArgumentType.getDouble(ctx, "x"));
                                    r.addProperty("y", DoubleArgumentType.getDouble(ctx, "y"));
                                    r.addProperty("z", DoubleArgumentType.getDouble(ctx, "z"));
                                    return run(ctx, r);
                                })));
    }
}
