# 完整生存系统使用指南

## 概述

AI 女仆现在拥有**完整的自主生存能力**：从零开始采集资源、制作工具、挖矿、建造、种田、探索，最终达成钻石装备。

## 核心能力

### 1. 生存线（从零到钻石）

女仆会自动按顺序完成以下目标：

```
采集木头 (16) 
  ↓
制作木镐 + 木斧
  ↓
挖石头 (64)
  ↓
制作石镐 + 石斧 + 石剑 + 熔炉 + 工作台
  ↓
挖煤炭 (32)
  ↓
挖铁矿 (24)
  ↓
烧制铁锭 (24)
  ↓
制作铁镐 + 铁剑 + 铁装备全套
  ↓
挖钻石 (3) [分层挖矿 Y=-59]
  ↓
制作钻石镐 + 钻石剑
```

### 2. 智能挖矿

- **扫描方块**：自动找附近 16 格内的矿石
- **分层挖矿**：没找到矿石时，移动到最佳层数（钻石 Y=-59，铁 Y=16）
- **隧道挖掘**：水平挖 2 格高隧道，每 3 格一条，扫描墙壁露出的矿石
- **工具检查**：石镐才能挖铁、铁镐才能挖钻石

### 3. 建筑系统

- **庇护所**：7x4x7 小屋（地板 + 4 面墙）
- **农场**：9x9 耕地
- **装饰**：床、箱子、画、花盆

### 4. 种田系统

- 自动找平地（附近有水）
- 锄地 → 种种子 → 等待成熟
- 收割成熟作物 → 重新种植
- 定期巡查农田

### 5. 探索系统

- **螺旋搜索**：从家向外一圈圈扩展（16 → 32 → 64 → ... → 256 格）
- **寻找村庄**：检测村民 → 标记村庄位置
- **地标记录**：矿洞入口、神殿（预留）

### 6. 智能调度

- **优先级**：生存威胁 > 当前目标 > 选择下一个
- **前置检查**：没镐子不挖矿、材料不够不合成
- **进度持久化**：存到 NBT，重启后恢复
- **LLM 决策**：预留接口（可让 LLM 选择下一步）

---

## 桥接口

### 查询目标

```json
{"cmd": "get_goals"}
```

响应：
```json
{
  "current_goal": "采集木头",
  "current_description": "收集 16 个原木",
  "completed": ["采集木头", "制作木工具"],
  "survival_path": [
    {"name": "采集木头", "completed": true},
    {"name": "制作木工具", "completed": true},
    {"name": "挖掘石头", "completed": false},
    ...
  ]
}
```

### 手动设置目标

```json
{"cmd": "set_goal", "goal": "GATHER_WOOD"}
```

可用目标：
- `GATHER_WOOD` - 采集木头
- `CRAFT_WOODEN_TOOLS` - 制作木工具
- `GATHER_COBBLE` - 挖石头
- `CRAFT_STONE_TOOLS` - 制作石工具
- `GATHER_COAL` - 挖煤
- `GATHER_IRON_ORE` - 挖铁矿
- `SMELT_IRON` - 烧铁锭
- `CRAFT_IRON_GEAR` - 制作铁装备
- `GATHER_DIAMOND` - 挖钻石
- `CRAFT_DIAMOND_TOOLS` - 制作钻石工具
- `BUILD_SHELTER` - 建造庇护所
- `BUILD_FARM` - 建造农场
- `HUNT_FOOD` - 狩猎食物
- `COOK_FOOD` - 烹饪食物
- `PLANT_CROPS` - 种植作物
- `HARVEST_CROPS` - 收割作物
- `EXPLORE_AREA` - 探索区域
- `FIND_VILLAGE` - 寻找村庄

### 查询状态

```json
{"cmd": "status"}
```

响应新增字段：
```json
{
  "maid": {
    ...
    "current_goal": "采集木头",
    "survival_progress": "2/10"
  }
}
```

---

## 新增事件

### 目标开始

```json
{
  "event": "goal_started",
  "who": "sagiri",
  "goal": "采集木头",
  "description": "收集 16 个原木"
}
```

### 目标完成

```json
{
  "event": "goal_completed",
  "who": "sagiri",
  "goal": "采集木头"
}
```

---

## 测试方法

### 方法 1：自动模式（完全自主）

1. 启动服务器，女仆上线
2. 她会**自动开始**第一个目标（采集木头）
3. 完成后自动进入下一个（制作木工具）→ 挖石头 → ...
4. 最终达成钻石装备

### 方法 2：手动指定目标

```python
import socket, json

s = socket.create_connection(("127.0.0.1", 8124))
s.sendall(b'{"cmd":"set_goal","goal":"GATHER_DIAMOND"}\n')
```

注意：如果前置条件不满足（比如没有铁镐），目标不会开始。

### 方法 3：给材料跳过前置

```python
# 直接给她铁镐，跳到挖钻石
{"cmd":"give","item":"iron_pickaxe","count":1}
{"cmd":"set_goal","goal":"GATHER_DIAMOND"}
```

---

## 观察进度

### 通过 RCON

```
/aimaid status
```

输出包含 `current_goal: 采集木头 (生存线进度 2/10)`

### 通过桥

```python
st = bridge.cmd("status")
print(st["maid"]["current_goal"])
print(st["maid"]["survival_progress"])
```

### 通过事件流

订阅桥的事件推送，实时看到：
```
【sagiri】目标开始：采集木头
【sagiri】挖掉了 minecraft:oak_log（10, -60, 5）
【sagiri】目标完成：采集木头
【sagiri】目标开始：制作木工具
```

---

## 调试技巧

### 查看日志

```
[AI-Maid] sagiri starting goal: 采集木头
[AI-Maid] sagiri goal progress: 采集木头 12/16
[AI-Maid] sagiri completed goal: 采集木头
[AI-Maid] sagiri starting goal: 制作木工具
[AI-Maid] sagiri crafted wooden_pickaxe x1
```

### 检查背包

```json
{"cmd":"inv"}
```

看她收集了哪些材料。

### 强制切换目标

```python
# 她卡在挖矿了，手动切回采木头
{"cmd":"set_goal","goal":"GATHER_WOOD"}
```

---

## 已知限制

1. **合成是简化版**：直接消耗材料 + 给成品，不模拟真实 GUI
2. **熔炉烧制**：同样简化（消耗材料 + 燃料 → 给成品）
3. **挖矿瞬间完成**：没有逐 tick 的工具速度/破坏动画
4. **分层挖矿可能挖到基岩**：Y=-59 往下挖会碰到 Y=-64（基岩层）
5. **探索不避开危险**：可能走进熔岩/悬崖
6. **种田需要手动给种子**：目前不会自动破坏草丛获取种子

---

## 下一步扩展（预留）

- **LLM 决策**：完成一个目标后，问 LLM "我该做什么？"
- **蓝图生成**：让 LLM 生成建筑蓝图（"盖个中世纪城堡"）
- **交易系统**：找到村庄后与村民交易
- **附魔台**：收集黑曜石 → 建附魔台 → 附魔装备
- **下界/末地**：挖黑曜石 → 建传送门 → 探索下界/击杀末影龙

---

## 提交信息

```
commit ef88202
feat: 完整生存系统（从零到钻石装备 + 建筑 + 探索 + 智能调度）

新增 6 个模块（~1320 行）：
- MaidGoals.java：20 个目标定义
- GoalExecutor.java：目标执行器
- MaidMining.java：智能挖矿（分层 + 扫描）
- MaidFarming.java：种田系统
- MaidExploration.java：探索 + 寻找村庄
- GoalScheduler.java：智能调度 + 进度持久化

配合之前的 Recipe + MaidCrafting，
女仆现在能从零开始生存到钻石装备。
```

仓库：https://github.com/iownmmiku/astrbot-ai-maid
