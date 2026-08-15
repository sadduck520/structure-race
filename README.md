# 🏁 Structure Race / 结构竞速

[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-orange)](https://www.minecraft.net)
[![Loader](https://img.shields.io/badge/Loader-Fabric-green)](https://fabricmc.net)
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

> A team-based exploration racing mod for **Minecraft 1.20.1 (Fabric)**.
> Form teams of up to 8 colors in a void lobby, then race to discover **structures** and **rare biomes** in the overworld to earn points for your team. First to reach the target score — or the highest score when time runs out — wins!

> 一个基于 **Minecraft 1.20.1（Fabric）** 的团队竞速探索模组。玩家在独立虚空大厅中组成 8 色队伍后进入世界竞速，通过发现**结构**与**稀有群系**、跑图、击杀等方式为队伍赚取积分，率先达到目标分数或时间结束时积分最高者获胜！

---

## ✨ Features / 特性

- **独立虚空大厅**：40×40 玻璃平台，8 种颜色队伍自由组队（GUI 指南针 / `/race join`）
- **两种获胜模式**：积分制（先到目标分获胜）/ 限时制（时间到最高分获胜），平局判定
- **结构计分**：发现即加分、同队不重复、**先到先得（占有制）**
- **群系计分**：首次踏入稀有群系加分，不占有，各队可分别获得
- **更多积分来源**：跑图里程、怪物击杀、维度探索（下界/末地）、林地府邸
- **落后补偿**：落后第一名 20 分以上的队伍自动获得速度提升
- **迷路指引**：3 分钟无发现自动提示最近结构坐标（个人计时、提示后 7 分钟冷却）
- **队伍召回**：消耗 10 分把队友召回身边（5 分钟冷却）
- **防作弊**：单次位移超 50 格视为传送，不计里程
- **多语言**：简体中文 / English，快捷键 **L** 或 `/language` 随时切换
- **双端安装**：客户端 + 服务端（或单机）均需安装

---

## 🎮 How to Play / 玩法流程

1. **进服**：先在主世界地底等待点加载世界数据（约 10 秒），随后自动进入大厅。
2. **组队**：右键背包中的**队伍选择器（指南针）**打开选队界面，或输入 `/race join <颜色>`。
3. **开始**：管理员输入 `/race start`，全体 **5 秒倒计时**后传送至主世界出生点，背包被清空，时间重置为白天。
4. **竞速**：探索世界，发现结构与群系为队伍赚取积分；聊天默认仅本队可见，`!` 前缀为全局消息。
5. **结束**：达到目标分 / 时间结束 → 公布排名并显示获胜队伍，10 秒后全体回到大厅，**多波次烟花**庆祝。

---

## 📜 Scoring / 计分规则

| 来源 | 规则 | 积分 |
|---|---|---|
| 结构 | 首次发现即加分；同队不重复；被任意队发现后其他队不得分 | 见分值表 |
| 群系 | 首次踏入目标群系加分；不占有，各队可独立获得 | 见分值表 |
| 里程 | 步行/飞行每 500 格 +1（坐船行驶不计；传送不计） | +1 / 500 格 |
| 击杀 | 每击杀 10 只敌对怪物 +1（含远程击杀，上限 200 只） | +1 / 10 只 |
| 维度 | 队伍首次进入下界 / 末地 | +10 / +20 |
| 林地府邸 | 携带探险家地图 / 无地图 | +50 / +30 |

### 结构分值（部分）
远古城市 25 · 堡垒遗迹 18 · 林地府邸 30/50 · 末地城 40 · 下界堡垒 15 · 要塞 30 · 沙漠神殿 8 · 雪屋 12 · 女巫小屋 12 · 掠夺者前哨站 8 · 丛林神庙 12 · 古迹废墟 12 · 废弃矿井 10 · 海底神殿 10 · 沉船 3/4 · 村庄 5 …（完整列表按 `P` 键查看）

### 群系分值（部分）
蘑菇岛 20 · 深暗之域 15 · 冰刺之地 8 · 沼泽 5 · 竹林 5 · 红树林 5 · 恶地 5 · 沙漠 5 · 丛林 4 · 樱花树林 4 · 黑森林 3 · 草甸 3 …（完整列表按 `P` 键查看）

---

## ⌨️ Hotkeys / 快捷键

| 键 | 功能 |
|---|---|
| **K** | 打开规则书（玩法指南） |
| **P** | 打开积分映射书（结构/群系分值） |
| **U** | 打开竞速进度书（本队进度 / 未找到的群系） |
| **L** | 打开语言选择界面（简体中文 / English） |

> 可在「选项 → 控制 → 按键绑定 → 结构竞速」中修改。

---

## 💬 Commands / 指令

### 玩家指令（无需 OP）
| 指令 | 说明 |
|---|---|
| `/race join <颜色>` | 加入/切换队伍（red/blue/yellow/orange/green/white/black/purple） |
| `/race leave` | 离开队伍成为观众 |
| `/race recall [玩家]` | 召回队友（耗 10 分，冷却 5 分钟） |
| `/race point` | 打开积分映射书 |
| `/race progress` | 打开竞速进度书 |
| `/language` `/lang` | 打开语言选择界面（或 `/language zh_cn` / `en_us` 直接指定） |

### 管理员指令（需要 OP 权限 2）
| 指令 | 说明 |
|---|---|
| `/race start` | 开始新一局（5 秒倒计时） |
| `/race stop` | 停止比赛（暂停计分） |
| `/race resume` | 恢复比赛 |
| `/race reset` | 重置比赛与所有玩家数据 |
| `/race mode <score\|timer>` | 切换获胜模式 |
| `/race config winscore <分>` | 设置积分制获胜分数 |
| `/race config duration <秒>` | 设置限时制时长 |
| `/race status` | 查看当前状态 |
| `/race time` | 查看剩余时间 |
| `/race top` | 查看排行榜 |
| `/race team add/remove <玩家>` | 强制分配/移出队伍 |
| `/race team list/info/check` | 查询队伍信息 |

---

## 🌐 Language / 语言

- 支持 **简体中文** 与 **English**，通过快捷键 **L**、`/language` 指令或大厅语言界面切换；
- 语言选择会**持久化到存档**，重进游戏仍保留；
- 规则书、积分映射书、进度书、聊天、广播、title、指令反馈均按玩家语言显示。

---

## 📥 Installation / 安装

1. 安装 [Fabric Loader](https://fabricmc.net/use/)（≥ 0.15.11）与 [Fabric API](https://modrinth.com/mod/fabric-api)（0.92.0+1.20.1）；
2. 将 `structure_race_v2-2.7.2.jar` 放入 **mods** 文件夹（客户端与服务端都放，单机只需客户端）；
3. 启动游戏，进入任意存档即可开始。

---

## 🛠️ Building / 构建

```bash
git clone <your-repo-url>
cd structure_race
gradlew build
```

构建产物位于 `build/libs/structure_race_v2-<version>.jar`。

**环境**：JDK 17 · Minecraft 1.20.1 · Yarn mappings · Fabric Loom 1.5

---

## ⚙️ Configuration / 配置

所有可调参数集中存放在 `StructureRaceConfig.java`：

- 获胜条件（`WIN_SCORE` / `MATCH_DURATION_SECONDS`）
- 检测参数（积分冷却、群系检测间隔）
- 迷路指引（3 分钟无发现 / 7 分钟冷却 / 检索半径）
- 进服等待时长（10 秒）、开赛倒计时（5 秒）
- 结构/群系分值映射、中文/英文名称映射

---

## 📁 Project Structure / 项目结构

```
src/main/java/com/example/structurerace/
├── StructureRaceMod.java               # 主入口（事件/指令/网络注册）
├── StructureRaceEvents.java            # 核心玩法逻辑（计分/大厅/结算/指引）
├── StructureRaceConfig.java            # 配置与分值映射
├── StructureRaceState.java             # 世界存档持久化（比赛/队伍/玩家进度）
├── StructureRaceCommand.java           # /race 指令注册
├── StructureRaceNetworking.java        # C2S 网络通道（快捷键请求）
├── Lang.java                           # 语言工具（中/英）
├── LanguageSelectorScreenHandler.java  # 语言选择 GUI
├── TeamSelectorScreenHandler.java      # 队伍选择 GUI
src/client/java/.../StructureRaceClient.java   # 客户端快捷键（K/P/U/L）
src/main/resources/data/structure_race/       # lobby 维度与结构 tag
```

---

## 🤝 License / 开源协议

[MIT](LICENSE) © 2026 YourName

欢迎提交 Issue 与 Pull Request！🚀
