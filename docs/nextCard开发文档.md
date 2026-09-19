# nextCard 开发文档

| 项 | 值 |
|---|---|
| 版本 | **v1.1**——v1.0 冻结后追加第五批裁定：**判断标签与通用 C 卡**（§5.5），已落地（logicTest 15/15） |
| 日期 | 2026-09-20 |
| 基础工程 | forge-1.20.1-mod-3（本工程，MC 1.20.1 / Forge 47.4.23 / MDG Legacy 工程化模板，当前占位 id `examplemod`） |
| 作者 | klze |
| 文档地位 | 想法 → 可行性验证 → 架构方向 → 三轮拍板记录 → 规格预冻结。**不含代码，工程未动**。开工确认后按 §7 里程碑执行 |
| 定位 | **适配整合包的框架型 mod**：本体只交付引擎 + 最小示例内容，具体卡表由使用者（整合包作者/用户）后续以 JSON 提供 |

---

## §0 摘要

- **九条原始想法全部可行且已全部拍板**（三轮裁定，映射见 §8）。三轮共引入三个新概念，全部落进结构：
  1. **三类卡**：A = 方向卡（开启次级体系，三级起入场）、B = 方式卡（战斗形式为题）、**C = 质变卡（改变机制，第三批新增）**；
  2. **体系（system）**：由方向卡声明的命名空间（基础行为 + 计数器），质变卡在体系挂点上做规则修饰——与修仙 mod「行为修饰通道」（槽位表 + 单点读取）同型；
  3. **整合包适配**：移除物品无内置获取途径，由整合包任务发放；mod 不内置经济，内容入口全部对整合包友好。
- 三条铁律已转成**六个核心抽象 + 特例消灭表 + 四个能失败的门**（§4）。抽卡引擎对卡类零特判——类只是候选过滤器字段；质变语义住在效果层，不进引擎。
- **微项已全部定案**（§8.3）：C1 质变卡 **T5 起**入场、C2 `requires` 语义确认、C3 挂点词表随首板卡表冻结。M0 已完成（`ae61ad2`）。
- **第五批裁定（v1.1）：判断标签与通用 C 卡**——标签可声明 `judgment_only`：不参与概率（不进权重、不建池）、只做 `requires` 判断；`requires` 统一改为**标签谓词**（拥有 ≥1 张带该标签的卡即满足）。一张通用 C 卡服务所有「类似机制」的体系，不必逐体系写卡（§5.5）。

---

## §1 想法复述（我读到的版本，已含三轮裁定）

编号对齐原始想法 1–9。

1. **池子**：池 = (等级，标签) 网格；池子不手工注册，由已注册卡片自动投影产生。**空池不进入抽取；全部可抽卡为空时显示「卡池无卡」**（第三批裁定 4）。
2. **卡片**：等级唯一，标签可多个。**三类**：A = 方向卡（**开启一个次级体系**，例：原体系是连击增伤，方向卡引入「每次攻击附着中毒」；三级起入场），B = 方式卡（以战斗形式为题，各级都有），**C = 质变卡（改变机制，例：连击增伤原本一次一次累加，质变卡让每次攻击算作「中毒层数」次攻击，快速叠加）**。
3. **抽卡方块**：功能方块，**结构生成**，**每人限用一次**，用后**不消失**。
4. **抽卡算法**：首抽 = 固定五张指定一级卡、**5 选 1**（第三批裁定 7）；之后半随机——已拥有卡的标签累积权重，对应标签池概率**渐近提升（上限 80%，渐近模型）**；未满部分纯随机；标签之间按数量权重分配；选中池后池内未拥有卡均匀出卡。**已选定卡踢出卡池**；**空池剔除，权重在非空池上归一**。
5. **次数与形态**：上限 15 次（含首抽）；每次展示 5 张选 1；**首槽必 A 从 A 入场（第 5 抽，随 T3）起生效**（第三批裁定 1），其余 4 张不分 A/B/C。
6. **效果**：卡片效果作用于玩家，**拥有即刻生效**；T1 概率后期**淡出**（第三批裁定 6）。
7. **注册**：JSON 注册式；**标签需要自己的注册**。
8. **UI**：一个界面，显示选中的卡与生效效果。
9. **移除物品**：指定移除一张已拥有卡，**退还一次抽卡次数**，被移除的卡**回到卡池**；生存一次性，创造无限；**无内置获取途径——由整合包任务领取**（第三批裁定 9）。

---

## §2 逐条可行性验证（终版）

| # | 想法 | 结论 | 状态 |
|---|---|---|---|
| 1 | 池子自动产生 | 可行，全案最漂亮的一条（池 = 投影，无注册口） | ✅ 全定 |
| 2 | 卡片三类 + 等级唯一 + 多标签 | 可行；C 类 = 质变卡新增，走机制修饰通道，引擎零特判 | ✅ 语义全定；挂点词表随首板卡表（C3） |
| 3 | 一次性抽卡方块 | 可行（BlockEntity 记已用 UUID 集，不消失） | ✅ 全定 |
| 4 | 标签加权抽卡 | 可行（纯函数；渐近模型；空池剔除 + 权重归一替代降级链——第三批裁定 4 更简洁） | ✅ 全定 |
| 5 | 15 次 / 5 选 1 / 首槽必 A | 可行（全数据化） | ✅ 全定（必 A 自入场抽次起） |
| 6 | 效果作用于玩家 | 可行（四原语已认可 + 机制修饰通道承接 C 类） | ✅ 全定；挂点词表 C3 |
| 7 | JSON 注册 | 可行（严格校验，fail-fast） | ✅ 全定 |
| 8 | 一个 UI | 可行（Screen + payload） | ✅ 全定 |
| 9 | 移除物品 | 可行（退次数 + 回池 = 改构筑循环；无内置来源，整合包发放） | ✅ 全定 |

---

## §3 对标参考

### §3.1 杀戮尖塔（Slay the Spire）

- 稀有度先抽、稀有度内均匀——两段采样，与「池子先选、池内出卡」同构；开局固定发牌 = 首抽。
- 删卡是核心机制（商店删卡服务）——本项目移除 + 退次数 + 回池，构成同等的「改构筑」循环。
- **质变卡对标 StS 的「形态卡/力量卡」**：改变规则本身而不是加数值（如恶魔形态、理性化）——C 类卡的游戏设计先例。

### §3.2 杀戮尖塔 2（本机反编译研究）

- Power 叠加语义 = 元数据五轴，不逐卡写逻辑——效果合并相（Q16 默认）的答案来源。
- 「先重算再通知」——与重算对账模型同型。

### §3.3 Minecraft 原版与生态

- loot table 两段加权 + `quality`/`luck` = 动态权重先例；但读不到玩家状态，权重模型自写纯函数。
- Cobblemon PokéStop / Lucky Block：世界交互站先例。
- Apotheosis / Puffish Skills：JSON 驱动内容注册、效果条款化。
- Mario Kart 道具分配：按玩家状态变权重，调手感对照。
- **FTB Quests 等任务系统**：整合包发放物品（移除物品、抽卡方块）的标准通道——本 mod 只需要保证「可被任务/指令/结构发放」，不内置经济。

### §3.4 自家工程

- **Spire Powers**：`powers/*.json`（polarity + effects[type/params]）→ 卡片效果子句起点。
- **修仙 mod**：存 id 不存实例、重算代替增删、内容删除门、门要能失败；**行为修饰通道（D-040：槽位表 + 单点读取，加机制 = JSON 或一个槽位）→ C 类质变卡的实现形状**。

---

## §4 架构总设计

### §4.0 模块图

```
nextcard/
├── core/                     ← 引擎：零内容知识，全部可无头测试
│   ├── card/      CardDefinition(codec) · CardIndex(id→定义) · 加载校验
│   ├── tag/       TagDefinition(注册) · 归一化
│   ├── pool/      PoolIndex(派生只读投影，无注册口) · 个人可抽池(全卡−已拥有−前置不满足)
│   ├── draw/      DrawEngine(纯函数) · DrawProfile · DrawSchedule · WeightModel
│   ├── effect/    EffectClause(子句 codec) · 机制修饰槽位(挂点) · EffectSnapshot · Reconciler
│   └── sim/       蒙特卡洛模拟器（概率门 G3 的被测物）
├── common/                   ← MC 粘合层，保持薄
│   ├── player/    PlayerCardState(capability：卡 id + 已抽次数 + 体系计数器) · 同步
│   ├── block/     DrawBlock + DrawBlockEntity(已用玩家 UUID 集)
│   ├── item/      CardRemoveItem
│   └── net/       SimpleChannel：开抽 / 选卡 / 卡库同步 / UI 数据
├── client/                   ← CardScreen（抽卡 5 选 1 + 卡库/效果页）
├── datagen/  gametest/  mixin/   ← 沿用模板
└── resources/data/nextcard/  ← 内容（首板卡表由用户后续提供，先交最小示例集）
    ├── cards/*.json  card_tags/*.json  draw_profiles/*.json  draw_schedule.json
    └── manifest.json(移除策略等全局数据)
```

### §4.1 六个核心抽象

1. **CardDefinition**——纯数据：`{ tier(单值 1–5), tags(集合), cardClass(A/B/C), system?(A 卡声明所属体系), requires?(前置体系 id 列表), effects(子句表) }`。加载校验（硬错误，fail-fast）：等级唯一、标签已注册、`cardClass=A ⇒ tier≥3`、`cardClass=C ⇒ tier≥5`（C1 已裁定）、A 卡必须声明体系、`requires` 引用的标签必须已注册、每卡至少一个非判断标签（否则无池可容、永远无法被抽到）、profile 引用的卡 id 必须可解析。**没有 stack 字段——每卡唯一拥有**。
2. **TagDefinition**——标签是一等公民：id/显示名/颜色/图标/描述/`judgment_only`（判断标签，v1.1），数据包注册。
3. **PoolIndex + 个人可抽池**——派生物：CardIndex 投影出 (tier, tag) → 卡集合，空池剔除，reload 重建，无注册口。玩家候选集 = 全卡 − 已拥有 − requires 标签谓词未满足（拥有 ≥1 张带该标签的卡即满足，v1.1 §5.5）。全是纯函数过滤，无每玩家结构。判断标签不投影池。
4. **DrawEngine（纯函数）**——输入 `(CardIndex 快照, DrawProfile, 已拥有卡集, RNG)`，输出 `(5 张候选, 决策日志)`。不碰方块、玩家、NBT、网络。**引擎对卡类零特判**：A/B/C 只是候选过滤器字段；首槽过滤 = `filter: {cardClass: A}`。
5. **DrawProfile / DrawSchedule（数据）**——首抽与标准抽都是 profile：

   ```json
   // draw_profiles/first.json —— 固定五张指定一级卡，5 选 1（第三批裁定 7）
   { "cards": ["nextcard:starter_1", "nextcard:starter_2", "nextcard:starter_3",
               "nextcard:starter_4", "nextcard:starter_5"],
     "grant": "pick_one" }

   // draw_profiles/standard.json —— 首槽必 A 自入场起生效（裁定：A 类开始时）
   { "slots": [
       { "filter": { "cardClass": "A" }, "sampling": "tag_weighted" },
       { "sampling": "tag_weighted" },
       { "sampling": "tag_weighted" },
       { "sampling": "tag_weighted" },
       { "sampling": "tag_weighted" } ],
     "grant": "pick_one" }

   // draw_schedule.json
   [ { "draws": "1",    "profile": "first" },
     { "draws": "2-4",  "profile": "standard_early" },   // 无 A 过滤槽（A 未入场）
     { "draws": "5-15", "profile": "standard" } ]
   ```

   **代码里没有 `if(firstDraw)`，没有 `if(cardClass==A)`。** profile 引用的卡 id 加载期硬校验（整合包改坏内容 → 明确报错，不静默）。
6. **EffectSnapshot + Reconciler（重算对账）+ 机制修饰槽位**——拥有卡集是唯一事实源。增/删卡 → 全量重算 → diff → 差量落到效果落点（属性修饰 / 事件入口 / tick 计数 / 即时）。**只有「重算」一条路**——抽到生效、移除回退、退次数，全部自动成立。C 类质变 = 机制修饰子句，走**修饰槽位**（见 §5.4），与抽卡引擎完全解耦。

### §4.2 特例消灭表（铁律 2 的台账）

| 想法里的特例 | 数据形态 | 代码里的唯一路径 |
|---|---|---|
| 首抽固定五张、5 选 1 | `draw_profiles/first.json` | DrawEngine 按 profile 跑 |
| 首槽必 A（自 A 入场起） | profile.slots[0].filter + schedule 分段 | 采样器统一过滤步骤 |
| 等级进度 / T5 第 9 抽 / T1 淡出 / 15 上限 | `draw_schedule.json` | schedule 查表 |
| 标签份额渐近 80% | WeightModel 参数（cap、W0） | 一个公式 |
| 选定卡踢出卡池 / requires 未满足 | 引擎输入 = 全卡 − 已拥有 − requires 未满足（v1.1：requires = 标签谓词，判断标签不进概率） | 同一个纯函数谓词，判断标签与普通标签无分叉 |
| 空池 / 个人池抽干 / 全部无卡 | 空池剔除 + 权重在非空池归一；全空 →「卡池无卡」 | 归一化一处 |
| C 类质变 | `mechanic_modifier` 子句 → 修饰槽位（§5.4） | 槽位表 + 挂点单点读取 |
| 移除退次数 / 生存一次性 | `manifest.json` remove_policy | 物品读数据 |
| 每人限用一次的方块 | BlockEntity 已用 UUID 集 | 集合成员判断 |
| 移除物品无内置来源 | ——（设计裁定：整合包发放） | mod 不内置经济 |
| 移除卡 → 效果回退 | —（结构保证） | Reconciler 重算 |
| 创造模式无限移除 | 原版 `instabuild` 惯例 | 原版语义，非特例 |

### §4.3 三条铁律 → 结构决定 → 门

**铁律 1：内容不得约束架构**
- 引擎包零卡片知识：删光 `data/nextcard/` 全部内容，mod 正常加载；抽卡返回「卡池无卡」；池子数 = 0 合法。首板卡表由用户后续提供（第三批裁定 12）——引擎先于内容成立。
- **门 G1（内容删除 GameTest）**：空内容集下，加载 / 池投影 / 抽卡引擎 / 重算全部可调用且行为可断言。

**铁律 2：不允许特例**
- §4.2 台账每行有数据出口；新机制先找数据形态。
- **门 G2（无特例断言）**：`core/` 反射扫描无具体卡 id / 标签 / 体系字面量；同一引擎喂两套 profile 数据产出不同行为；任意内容集下首抽满足 profile 声明；**A/B/C 对引擎只是过滤器字段（engine 内无 class 分支）**。

**铁律 3：架构极致优化、压缩、优雅**
- 一张表（card）、一个投影（pool）、一个纯函数（draw）、一条重算路径（effect）、一个 capability（卡 id + 次数 + 计数器）、一条通道（约 6 个 payload）、一屏（UI）、一物品（移除）。核心引擎软预算 ≤ 1500 行。
- **门 G3（概率蒙特卡洛）**：1 万次抽卡输出各池占比与标签份额表；断言：`0 ≤ S < 0.8`；空卡集 S=0；S 单调不减；T5 不早于第 9 抽；**任意 15 抽路径不撞「全空死局」（个人池耗尽时归一化仍能给出结果或正确显示卡池无卡）**。先 sim 后实装。
- **门 G4（无头回归）**：概率与池投影走 JavaExec 纯逻辑测试（工作区 `test` 任务中文路径坑——一律 JavaExec runner）；GameTest 覆盖方块每人一次闭环、并发用块、移除退次数 + 回池。

### §4.4 技术落点（1.20.1 Forge 47.4.23，全部现成机制）

| 需求 | 机制 | 备注 |
|---|---|---|
| JSON 卡片加载 | `SimpleJsonResourceReloadListener` + `AddReloadListenerEvent` | 文件名 = id；codec 严格校验 |
| 客户端可见卡定义 | 登录/重载后 payload 全量同步 | UI 读本地副本 |
| 拥有卡/次数/体系计数器 | Player capability（`ICapabilitySerializable` + `PlayerEvent.Clone`） | 只存 id、计数、计数器值（修仙教训：存 id 不存实例） |
| 每人一次的方块 | BlockEntity 记「已用玩家 UUID 集」 | 方块不消失；NBT 持久化 |
| 效果落点 | 属性 = `AttributeModifier`（UUID 由卡 id 派生）；事件 = 统一入口查表；周期 = `PlayerTickEvent` 计数；即时 = 抽到瞬间 | 四原语（第三批裁定 11 认可，不够再改——开放词表） |
| C 类质变 | 机制修饰槽位（§5.4） | 挂点单点读取，加机制 = JSON 或一个槽位（修仙 D-040 同型） |
| 移除物品 | 选择 Screen → 服务端改集 → 重算 → 退次数 | survival `shrink` / creative 不消耗；策略进 manifest |
| 网络 | SimpleChannel（模板已配） | 服务端权威；客户端只读快照 |

### §4.5 整合包适配约定（第三批裁定 9/12 派生）

- **无内置经济**：移除物品、抽卡方块不写合成表/战利品/商店——发放方式（任务奖励、指令 `/give`、结构箱子、战利品表）全部留给整合包。mod 保证：两者都是普通 `Item`/`BlockItem`，可被任何任务系统引用。
- **结构生成**为抽卡方块的默认来源（第一批裁定 4），随 mod 附带最小示例结构；整合包可禁用/替换。
- **JSON 编写指南**是交付物（M3）：卡片/标签/日程/语义四类文件的字段手册 + 校验错误对照表——内容作者（用户、整合包作者）不需要读代码。
- **fail-fast 校验**：内容写坏 → 加载期明确报错定位到文件与字段，绝不静默跳过（DFU `optionalFieldOf` 教训）。

---

## §5 抽卡算法、等级日程与卡类语义

### §5.1 等级日程（已认可，权重可调 JSON；T1 淡出已裁定）

| 抽次 | T1 | T2 | T3 | T4 | T5 | 备注 |
|---|---|---|---|---|---|---|
| 1 | 首抽 profile | | | | | 固定五张指定一级卡，5 选 1 |
| 2–4 | 70 | 30 | — | — | — | |
| 5–8 | 45 | 35 | 20 | — | — | T3（及 A 卡）入场，首槽必 A 从此生效 |
| 9–11 | 30 | 30 | 25 | 10 | **5** | T5 首现 |
| 12–15 | **10** | 30 | 25 | 15 | **20** | T1 淡出，T5 递增（第三批裁定 6） |

### §5.2 三类卡语义（终版）

| 类 | 名 | 语义 | 入场 | 引擎视角 |
|---|---|---|---|---|
| A | 方向卡 | **开启一个次级体系**：声明体系 id + 基础行为（+ 计数器）。例：原体系连击增伤 → 方向卡引入「每次攻击附着中毒」 | **T3 起**（随三级卡入场，第一批裁定 2） | `filter: {cardClass: A}`，无特判 |
| B | 方式卡 | 以战斗形式为题的效果卡，各级皆可 | T1 起 | 普通候选 |
| C | 质变卡 | **改变机制**：在体系挂点上做规则修饰。例：连击增伤原本一次一次累加 → 质变卡让每次攻击算作「中毒层数」次攻击 | **T5 起（C1 已裁定）** | `filter: {cardClass: C}`，无特判 |

### §5.3 标签份额（渐近模型，已裁定）

```
第 1 阶段  定等级   schedule 查表 → §5.1 权重行；该行内无候选的等级剔除，权重在剩余等级归一
第 2 阶段  定池子   该等级内候选 = 该池未拥有且 requires 满足的卡；空池剔除；判断标签不进 W（v1.1）；
                      W_t = 拥有卡中带标签 t 的张数（每卡计 1，多标签卡对每个标签各计 1）
                      标签总份额 S = cap × ΣW / (ΣW + W0)，cap = 0.8，W0 = 8
                      未满部分（1 − S）→ 纯随机支；标签之间按 W_t / ΣW 分配 S
                      无候选的标签权重作废，损失份额归随机支
第 3 阶段  定卡     标签支：在 (等级，标签) 池的候选里均匀
                    随机支：该等级全部候选均匀
全空兜底   所有等级无任何候选 → 不抽卡，显示「卡池无卡」，方块不消耗
```

- 渐近性质（断言）：`0 ≤ S < 0.8`；空卡集 S=0；S 单调不减。
- 参考手感（W0=8）：ΣW=5 → S≈31%；ΣW=10 → 44%；ΣW=20 → 57%；ΣW=40 → 67%。
- **「空池剔除 + 归一化」统一处理了内容缺口、个人池抽干、前置过滤三种情况——没有降级链，没有特例**（第三批裁定 4 取代 v0.2 的降级重试默认）。

### §5.4 体系、计数器与质变修饰（C 类的实现形状）

- **体系（system）由 A 卡声明**（内容驱动，无需体系注册表）：A 卡 JSON 的 `system` 字段 + 它的子句定义该体系的基础行为与计数器。体系存在 ⇔ 定义它的 A 卡存在——删掉内容体系随之消失，铁律 1 自动成立。
- **计数器**：体系可声明计数器（如中毒层数），值存玩家 capability 的 counter map（`system:counter → value`），随体系移除（方向卡被移除）清零。重算对账负责计数器声明的生效/失效。
- **质变修饰子句（草案，随首板卡表冻结挂点词表）**：

  ```json
  { "type": "mechanic_modifier",
    "target": { "system": "combo",  "hook": "attack_count_input" },
    "source": { "system": "poison", "counter": "stacks" },
    "op": "count_as" }
  ```

  含义：连击体系的「攻击计数输入」挂点上，每次攻击按中毒体系的层数计次——即第三批裁定 3 的例子。`target.hook` 是开放词表；`source` 可跨体系引用（常量也行：`{"const": 3}`）。
- **落点形状 = 修仙 mod D-040 行为修饰通道**：槽位表 + 每槽一个读取点；加新机制 = 一个挂点或一条 JSON，不改数值代码。
- **通用 C 卡的效果侧草案（随首板卡表冻结，C3）**：判断标签解决了「入场」，效果侧的泛化形态是 source 通配——`{ "source": { "tag": "nextcard:counter", "counter": "stacks" } }` = 拥有卡中所有带 counter 标签的体系各自的 stacks 计数器逐体系生效。未定项：多体系并存时逐体系独立（推荐，修饰槽位天然支持）还是取最大。

### §5.5 判断标签与通用 C 卡（v1.1，第五批裁定）

**裁定原文**：C 类可以拓展通用——将类似机制的卡打上「不参与概率、只参与判断」的标签，C 类卡满足判断就加入卡池，极大减少为每个体系设置类似 C 卡的工作量。

**落地形态**：

1. **判断标签**：`card_tags/*.json` 里 `"judgment_only": true`。不进标签权重 W_t、不投影 (等级，标签) 池；唯一用途 = `requires` 谓词。
2. **requires 统一为标签谓词**：`"requires": [标签 id]`，满足 = 拥有 ≥1 张带该标签的卡。判断标签与普通标签走**同一个谓词**（用普通标签做条件也合法，如「拥有任一火系卡」）——无特例。v1.0 的「requires 指向体系」语义被本机制取代：体系入场判断改由「A 卡（或该体系家族卡）携带的判断标签」表达。
3. **通用 C 卡示例**：判断标签 `nextcard:counter`（计数体系家族）打在 venom_edge（毒 A 卡）与 twin_fang 上；通用 C 卡 `chain_reaction`（每次攻击算作计数层数次攻击）`requires: [nextcard:counter]`——玩家拥有任一计数家族卡即入池，**毒/燃/流血等每个新计数体系都不需要再写对应 C 卡**，只给 A 卡打上 `nextcard:counter` 即可。
4. **效果侧的泛化**（source 通配）见 §5.4 末条，随首板卡表冻结（C3）。
5. **孤儿卡**（数据包删卡后）不携带标签信息 → 不满足任何判断（与 Q22 失效卡语义一致）。
6. **加载校验**：requires 必须引用已注册标签；每卡至少一个非判断标签（否则无池可容、永远无法被抽到）。

---

## §6 数据格式草案

卡片（`data/nextcard/cards/venom_edge.json`，文件名 = id）——一条 A 卡（方向卡）示例：

```json
{
  "tier": 3,
  "card_class": "A",
  "system": "poison",
  "tags": ["poison", "attack"],
  "effects": [
    { "type": "counter",   "system": "poison", "counter": "stacks",
      "gain": { "on_event": "attack", "amount": 1 } },
    { "type": "periodic",  "tick_rate": 40,
      "action": "damage_per_counter", "system": "poison", "counter": "stacks",
      "amount": 0.5 }
  ],
  "name": "card.nextcard.venom_edge",
  "texture": "venom_edge"
}
```

一条 C 卡（质变卡）示例：

```json
{
  "tier": 4,
  "card_class": "C",
  "requires": ["poison"],
  "tags": ["poison", "attack"],
  "effects": [
    { "type": "mechanic_modifier",
      "target": { "system": "combo", "hook": "attack_count_input" },
      "source": { "system": "poison", "counter": "stacks" },
      "op": "count_as" }
  ],
  "name": "card.nextcard.venom_cascade",
  "texture": "venom_cascade"
}
```

标签（`data/nextcard/card_tags/fire.json`）：`{ "name": "tag.nextcard.fire", "color": "E25822", "icon": "fire", "description": "…" }`

判断标签（`data/nextcard/card_tags/counter.json`，v1.1 §5.5）：

```json
{ "name": "tag.nextcard.counter", "color": "8BC34A", "icon": "counter",
  "description": "…", "judgment_only": true }
```

通用 C 卡（`data/nextcard/cards/chain_reaction.json`，判断满足即入池）：

```json
{
  "tier": 5, "card_class": "C", "requires": ["nextcard:counter"],
  "tags": ["nextcard:poison", "nextcard:attack"], "effects": [],
  "name": "card.nextcard.chain_reaction", "texture": "chain_reaction"
}
```

全局策略（`data/nextcard/manifest.json`）：`{ "remove_item": { "refund_draws": 1, "consume_survival": true } }`

profile / schedule 见 §4.1-5、§5.1。原则：**字段全部可校验，未知字段报错**；字段手册（编写指南）随 M3 交付。

---

## §7 里程碑

| 切片 | 内容 | 出口 |
|---|---|---|
| **M0 改名与地基（✅ 2026-09-20 完成）** | mod_id=nextcard、包 `com.klze.nextcard`、作者 klze；core/ 六抽象（CardDefinition / TagDefinition / PoolIndex / DrawEngine / DrawProfile+DrawSchedule / EffectClause+Reconciler 全部纯逻辑实现）+ `sim/`（在 core 外，允许引用内容）+ 示例内容 16 卡 3 标签 | `build` + `logicTest`（13/13）+ `runData` + `runGameTestServer` 全绿 |
| M1 抽卡闭环 | MC 粘合：reload 监听器 / 方块 / capability / 网络 / 最小 UI（5 选 1） | G4 GameTest：方块每人一次闭环 + 并发 |
| M2 效果与移除 | 四原语 + 机制修饰槽位落地 + 移除物品（退次数 + 回池）+ 卡库页 | 删卡回退、退次数 GameTest 绿；C 类质变用例绿 |
| M3 内容接入 | **你提供首板卡表** → 挂点词表冻结（C3）→ 卡片/标签 JSON 化；JSON 编写指南交付；模板化美术管线（边框色 = 等级、角标 = 标签，第三批裁定 13） | 全部内容过严格校验；G3 用真实分布复跑 |
| M4 打磨发布 | 引导、平衡（sim 调参）、简中界面（裁定 13）、`publishMods` | 发布 |

### §7.1 M0 实施记录（门验证了门自己）

- **G3 当场抓到一个真 bug**：槽位过滤（首槽必 A）先于日程等级权重执行，`venom_edge` 被拥有后，唯一剩下的 A 卡是 T5 的 `phoenix_breath`，权重全零触发均匀回退 → T5 在第 6 抽被抽出。修正为：**日程是更高法则**（只在正权重等级内抽），槽位过滤只在日程允许的等级内收紧（§5.3 已按此口径表述）。
- **DFU `optionalFieldOf` 陷阱现场重演**：`tier_weights` 用 `Codec.INT` 做 map 键在 JsonOps 上解析失败，被 `optionalFieldOf` 静默吞成空表——与踩坑记录（DFU optionalFieldOf swallows errors）完全一致；修复 = 键经字符串 flatXmap 解码（`DrawSchedule.TIER_WEIGHTS_CODEC`）。
- **G2 连注释一起查**：`ContentReader` javadoc 里的示例卡 id 被门抓出——引擎包零内容字面量，注释也算。
- 工作区 `test` 任务按模板约定跳过（中文路径），纯逻辑验证走 `logicTest`（JavaExec），`check` 已挂接，`build` 自动执行。
- **v1.1 追加（第五批裁定落地）**：`TagDefinition.judgmentOnly` + `PoolIndex`/`TagWeights` 排除判断标签 + `requires` 标签谓词化 + 加载校验（坏引用 / 纯判断标签卡拒绝）；示例内容 +`counter` 判断标签 + 通用 C 卡 `chain_reaction`（17 卡）；logicTest **15/15** 绿（新增 `judgmentTagGatesButNeverWeights`、`contentValidationRejectsBadRequiresAndMarkerOnlyCards`）。

---

## §8 拍板记录

### §8.0 第一批裁定映射（回复 1–9 → 问题）

见 v0.2 记录，摘要：等级日程（T5 第 9 抽）/ A=方向卡、B=方式卡、随三级入场 / 无同名卡、选定踢池 / 结构生成每人一次不消失 / 首抽固定五张 / 渐近曲线 / 即刻生效 / 移除退次数 / 标签注册。

### §8.1 第三批裁定映射（回复 1–13 → 问题）

| 你回的 | 问题 | 裁定 | 落点 |
|---|---|---|---|
| 1 | N1 首槽必 A 冲突 | A 类开始（入场）时生效 | §5.1、§5.2 |
| 2 | N2 方向卡机制 | 方向卡 = 次级体系本身（原体系连击增伤 → 方向卡引入每次攻击附着中毒） | §5.2、§5.4 |
| 3 | 新灵感 | **新增 C 类 = 质变卡**（改变机制；例：每次攻击算作中毒层数次攻击） | §5.2、§5.4 |
| 4 | N8 + Q5 空池 | 空池不加入抽取；全空显示「卡池无卡」——取代降级链，更简洁 | §5.3 |
| 5 | N3 权重表 | 认可 | §5.1 |
| 6 | N3 T1 后期 | 淡出 | §5.1（12–15 抽 T1 降至 10） |
| 7 | N4 首抽 | 只选一张 | §4.1-5（pick_one；固定五张指定卡 + 计入 15 次一并锁定） |
| 8 | N5 已用提示 | 可以（聊天栏提示） | §4.4 |
| 9 | N6 移除物品来源 | 无内置获取途径；整合包任务领取——**mod 定位 = 适配整合包的框架型 mod** | §4.5 |
| 10 | N7 卡回池 | 对 | §5.3 |
| 11 | Q15 效果原语 | 可以（四原语），不够再改 | §4.4（开放词表） |
| 12 | Q23 内容量 | 首板卡表由你在框架完成后提供 | §4.5、§7-M3 |
| 13 | Q24 美术/语言 | 可以（模板化美术 + 简中） | §7-M3/M4 |

### §8.1.1 第五批裁定（2026-09-20，v1.1）

| # | 裁定 | 落点 |
|---|---|---|
| 1 | **判断标签**：标签可声明 `judgment_only`——不参与概率（不进权重、不建池），只做 requires 判断；C 类卡满足判断即入池，一张通用 C 卡服务所有类似机制体系 | §5.5、§4.1-2/3、§6 |
| — | 随本裁定，`requires` 从「体系引用」统一改为「标签谓词」（拥有 ≥1 张带该标签的卡即满足），判断标签与普通标签同一谓词，全类可用无分叉 | §4.1-1、§5.3 |

### §8.2 按推荐默认执行、未否决即生效

Q3（随机支 = 日程行内候选均匀）· Q4（不做稀有度维度）· Q6（权重 = 拥有卡张数，最近选择不加成）· Q8（5 张同一算法）· Q9（同次去重）· Q16（合并相 sum/mult/max）· Q18（死亡保留全部状态）· Q20（无卡时移除物品提示不消耗）· Q22（孤儿卡 = 失效卡灰显）。

### §8.3 微项定案与开工记录（2026-09-20 第四批回复）

- **C1**：质变卡入场等级 = **T5 起**（加载校验 `cardClass=C ⇒ tier≥5`）。已落 §4.1-1 / §5.2。
- **C2**：`requires` 语义确认 = 前置体系未拥有 ⇒ 该卡不进个人可抽池（效果因体系不存在自然无效——双保险同一机制）。
- **C3**：挂点词表随首板卡表冻结，框架期预留 attack / tick / damage 三个通用挂点 + `count_as` / `multiplier` / `override` 三个操作符草案。
- **开工确认**：已收到，冻结 v1.0，M0 开工（改名 + 六抽象骨架 + G1 门）。LICENSE 维持 All Rights Reserved（未收到换 MIT 的指示，如要换随时说）。

---

## §9 风险清单

| 风险 | 应对 |
|---|---|
| 概率手感拍脑袋 | G3 蒙特卡洛先跑；参数全在 JSON；先 sim 后实装 |
| 挂点词表设计不足（C 类语义在真实卡表前无法完全冻结） | 框架期只留三个通用挂点 + 三个操作符；词表随首板卡表冻结（C3）；加挂点 = 一个读取点，成本可控 |
| 个人池耗尽（踢池 × 15 抽） | 空池剔除 + 归一化；G3 断言任意 15 抽路径不撞死局 |
| 内容作者写坏 JSON | fail-fast 校验 + 字段手册；错误定位到文件与字段 |
| 整合包滥发移除物品破坏节奏 | 退次数只改不改多（上限 15 恒定）；发放量由整合包自裁（定位即如此） |
| 多人并发用块 / 同时抽 | 方块交互服务端原子化；GameTest 并发用例 |
| 数据包热重载 | reload 重建 PoolIndex；孤儿卡 = 失效卡（Q22 默认） |

---

## §10 工程改名清单（M0 执行，现在不动）

- `gradle.properties`：`mod_id=nextcard`、`mod_name=NextCard`、`mod_authors=klze`、`mod_group_id=com.klze.nextcard`、description（一句话，你来定）。
- 包名 `com.example.examplemod` → `com.klze.nextcard`（全包移动 + 主类改名，模板 AGENTS.md 目录约定平移）。
- `mods.toml` 由 properties 注入，无需手改；README / AGENTS.md 同步改名说明。
- LICENSE 现为 All Rights Reserved；要不要换（MIT / 保留 ARR），开工时说一句。
