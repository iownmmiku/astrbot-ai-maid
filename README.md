# AstrBot AI Maid

让 AstrBot 拥有一个**活在服务器里的 AI 玩家**。

不是协议客户端——是服务端的一个**假玩家**（`ServerPlayerEntity` + 假连接），
所以它拥有人形/皮肤/背包/饱食度，走的是**原版玩家物理**，普通客户端能直接看见它。

> 服务端 mod（server-only）。装在服务端即可，玩家不需要装任何东西。

## 它现在能做什么

| 能力 | 说明 |
|---|---|
| 自然移动 | 每 tick 喂移动输入 + 手动 `tickMovement()`，速度 = 原版走路 4.317 格/秒 |
| A* 寻路 | 自写寻路（服务端直接读世界，无需解析区块），会绕障碍、上 1 格台阶、下坡 |
| 背包 / 饱食度 / 生命 | 全部原版行为，会写进 `playerdata`（重启还在） |
| 挖掘 / 放置 / 拾取 | 走原版 `ServerPlayerInteractionManager` |
| **完整合成系统** | 工作台 + 熔炉，递归合成前置材料（木板→木棍→木镐） |
| **智能挖矿** | 扫描方块找矿、分层挖矿（Y=-59 挖钻石）、隧道挖掘 |
| **自动生存** | 饿了吃、受伤躲、有敌人打，完全自主 |
| **从零到钻石装备** | 20 个目标（采木头→石镐→铁装备→钻石镐），自动执行 |
| **建筑施工** | 蓝图驱动，自动检查材料、排序、依次放置 |
| **种田系统** | 锄地→种→浇水→收割→重新种植 |
| **探索世界** | 螺旋搜索、寻找村庄、标记地标 |
| **智能调度** | 优先级管理、前置检查、进度持久化、LLM 决策接口 |
| 外部控制 | TCP + JSON 双向桥，支持**主动事件推送** |
| RCON 指令 | `/aimaid ...` |

## 环境

- Minecraft **1.20.1** + Fabric Loader 0.15+
- Fabric API 0.92.x
- Java 17+（开发用 21）

## 构建

```bash
./gradlew build
# 产物：build/libs/astrbot-ai-maid-<version>.jar  →  丢进服务端 mods/
```

## 指令

```
/aimaid spawn                  在指令来源处刷出女仆（默认名 sagiri）
/aimaid status | inv | pos     状态 / 背包
/aimaid goto <x> <z>           A* 走过去（绝对坐标）
/aimaid mine <x> <y> <z>       挖
/aimaid place <x> <y> <z>      放
/aimaid give <item> [count]    给东西
/aimaid hold <item>            拿在手上
/aimaid say <text>             在聊天里说话
/aimaid stop | jump
/aimaid remove
```

## 桥（给 AstrBot 用）

默认监听 `127.0.0.1:8124`，一行一个 JSON。

**下发指令**

```json
{"id":1,"cmd":"goto","x":10,"z":20}
{"id":2,"cmd":"mine","x":6,"y":-60,"z":12}
{"id":3,"cmd":"place","x":6,"y":-60,"z":14}
{"id":4,"cmd":"give","item":"oak_log","count":32}
{"id":5,"cmd":"hold","item":"stone"}
{"id":6,"cmd":"say","text":"hello"}
{"id":7,"cmd":"status"}
{"id":8,"cmd":"spawn","x":0,"y":-59,"z":0}
{"id":9,"cmd":"gamemode","mode":"survival"}
{"id":10,"cmd":"stop"}
{"id":11,"cmd":"set_goal","goal":"GATHER_DIAMOND"}
{"id":12,"cmd":"get_goals"}
```

**响应**：`{"id":1,"ok":true}` / `{"id":1,"ok":false,"error":"..."}`

`status` 会带回 `maid` 对象：坐标、血量、饱食度、手上物品、当前任务、**当前目标**、**生存进度**、背包。

`get_goals` 返回当前目标、已完成的目标、生存线进度。

**主动推送的事件**（不需要轮询）

```json
{"event":"arrived","who":"sagiri","x":..,"y":..,"z":..}
{"event":"mined","block":"minecraft:oak_log","x":..,"y":..,"z":..}
{"event":"placed","x":..,"y":..,"z":..}
{"event":"hurt","health":18.0,"delta":-2.0}
{"event":"food","food":17}
{"event":"chat","name":"玩家名","text":"..."}
{"event":"goal_started","goal":"采集木头","description":"收集 16 个原木"}
{"event":"goal_completed","goal":"采集木头"}
{"event":"build_complete","blocks":27}
```

完整文档：[SURVIVAL_GUIDE.md](SURVIVAL_GUIDE.md)

## 已知问题 / 待办

- **皮肤**：需要走 Mojang 签名校验。本地图片在正版客户端上会被拒绝
  （`Signature is missing from Property textures`），必须用一个**正版账号上传皮肤**后取其签名属性。
- 挖掘目前是一次性完成（未做逐 tick 的工具速度/破坏动画）。
- 攻击、种田、建蓝图尚未实现。
- 桥只监听 127.0.0.1，无认证（本机使用没问题）。

## 目录

```
src/main/java/com/iownmmiku/maid/
  AiMaidMod.java          入口：起皮肤服务、桥、tick
  Maids.java              假玩家的生成/查找
  FakeClientConnection.java  假连接（KeepAlive 当场回，避免被踢）
  MaidBrain.java          每 tick 喂输入 + 跑原版物理 + 跟随路径
  Pathfinder.java         A*
  MaidActions.java        看/挖/放/给/拿/查背包
  MaidApi.java            对外 API（指令与桥共用，绝对坐标）
  BridgeServer.java       TCP + JSON 桥
  MaidEvents.java         事件推送
  SkinServer.java         本地皮肤 HTTP
  AiMaidCommands.java     /aimaid 指令
```
