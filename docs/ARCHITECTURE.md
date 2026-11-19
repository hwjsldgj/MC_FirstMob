# 架构与耦合评估

回答两个问题：**模组内容对原版的依赖有多强**、**当前架构能否支撑长期开发**。结论附带可执行的演进路线，供后续迭代排期。

> 本文是评估与计划，不是行为规范。行为与数值以 [SPEC](SPEC.md) 为准，接口以 [API](API.md) 为准。

## 结论

- **原版依赖：分层看，中等偏强。** 资源与注册完全自主；行为层靠继承（`LlamaEntity`、`LlamaSpitEntity`）与 Mixin（`LivingEntity`、`PlayerEntity`）实现，属于升级时的第一波风险点。
- **长期可靠性：短期可用，长期需要拆。** 核心逻辑集中在单个约 2000 行的实体类里，状态靠布尔字段与 `tick()` 调用顺序表达，除数值表外没有测试兜底。继续加功能会在同一个类里叠加分支。
- 两条都不需要重写：`CombatProfile` 的抽表方向正确，按下面的路线增量演进即可。

## 一、原版依赖现状

### 自主的部分

| 项 | 说明 |
| --- | --- |
| 注册与 ID | 实体、刷怪蛋、6 个音效事件、2 个伤害类型均为 `hush:` 命名空间 |
| 数值逻辑 | `CombatProfile` 为纯数据表，不引用原版类，有单元测试 |
| 伤害类型 | `data/hush/damage_type/*.json` 自带 `scaling: never`，不复用原版类型 |
| 资源 | 语言、刷怪蛋模型与贴图、图标均为自有文件 |

### 依赖原版的部分

| 位置 | 形式 | 强度 |
| --- | --- | --- |
| `YaoYongshouEntity extends LlamaEntity implements Vibrations` | 继承整只羊驼并实现原版振动监听接口 | 高 |
| `YaoYongshouSpitEntity extends LlamaSpitEntity` | 复用原版弹射物物理与命中判定 | 高 |
| `LivingEntityArmorMixin` → `LivingEntity#applyArmorToDamage` | 注入原版护甲公式，全局生效（仅对自定义 `DamageSource` 改数值） | 高（最脆） |
| `PlayerSoundMixin` → `PlayerEntity#playSound` | 拦所有玩家的发声，靠方法描述符定位 | 高（最脆） |
| `sounds.json` | 6 个事件全部指向 `minecraft:entity.llama.*` / `warden.*`，仅替换字幕 | 中 |
| AI 目标、粒子、`StatusEffects`、`ServerBossBar`、`GameEventTags` | 直接复用原版实现 | 中（常规用法） |

**风险不在内容，在升级**：`applyArmorToDamage` 的签名、`playSound(SoundEvent,FF)` 的描述符、`LlamaEntity` 的商队/骑乘/繁殖行为，任何一次 Yarn 改名或行为调整都会直接编译失败或静默失效。资源层（JSON、贴图、音效）几乎不受影响。

## 二、长期可靠性

### 现状数据

| 指标 | 实际 |
| --- | --- |
| 主实体 | `YaoYongshouEntity.java` 约 1990 行、约 100 个方法、7 个内部类（6 个 Goal + 1 个振动回调） |
| 其余主类 | 20~134 行，职责清晰 |
| 单测 | 仅覆盖 `CombatProfile`；行为逻辑无覆盖 |
| 状态载体 | 多个布尔/整型字段（`bossForm`、`bossFormDormant`、`sonicMode`、`revengeMode`、`curiousPos`、`phase` 等） |
| 持久化 | NBT 仅保存 `BossForm`、`BossFormDormant`；愤怒值、好奇目标读档即丢 |
| 网络同步 | 仅 `ATTACK_START_AGE` 一个 `TrackedData` |

### 会随规模放大的问题

1. **上帝类**：听觉与愤怒、阶段与形态、Boss 战、瞬移寻路、音波、粒子、BossBar、繁殖与近战覆写都在一个类里，改一处容易被另一处的状态判断影响，且没有测试兜底。
2. **隐式状态机**：状态流转靠 `tick()` 的调用顺序（`decayAnger` → `updateBossCombat` → `updateBossFormRevert` …），字段间存在隐含互斥（`bossForm` 与 `sonicMode`、`revengeMode` 与 `sonicMode`）。这些约束只写在注释与 SPEC 里，编译器不检查。
3. **数值仍部分硬编码**：`CombatProfile` 只收走了伤害、距离、击退；追击上限、粒子间隔、瞬移尝试次数等仍在方法体内，服主无法调整。
4. **升级面集中**：两个 Mixin 的注入点与 `LlamaEntity` 继承，是每次 Minecraft 版本变动的首要故障点。

### 可靠的部分（保持不变）

- 包划分（`combat` / `entity` / `mixin` / `registry` / `client`）清晰。
- `CombatProfile` 抽表 + 单测，是可测试性的正确起点。
- SPEC 作为行为单一事实来源，验收用例可执行。
- Mixin 只有 2 个，且都用 `instanceof` 收窄，影响面可控。

## 三、参考方案

按收益排序，全部为增量改动，不做重写；每一步单独提交、单独验证。

### 第 1 步：拆听觉与愤怒（优先）

- 抽出 `HearingSensor`：振动监听转发、音效补充、音效与 `GameEvent` 的 2 tick 配对窗口、遮挡判断。
- 抽出 `AngerTracker`：加分、每玩家独立计数、衰减、主嫌解析。
- 预期：主实体减少约 300~350 行；两块计数逻辑可直接单测。

### 第 2 步：状态显式化

- 引入 `enum Form { CALM, ALERT, BOSS }` 与单一 `transition(...)`，收拢 `bossForm` / `bossFormDormant` / `sonicMode` / `revengeMode` 的隐含约束。
- 明确决定愤怒值与好奇目标是否写入 NBT；若维持「读档重置」，在 SPEC 中写明。

### 第 3 步：拆战斗与移动

- `PhaseController`：阶段判定、属性基值、阶段增益、脱战恢复。
- `SonicAttack`：充能、发射、冷却。
- `TeleportPlanner`：落点搜索与空间判定，做成可纯函数化的判定逻辑，便于测试。

### 第 4 步：数值收口

- 把剩余常量并入 `CombatProfile` 或独立的 `Tuning`，再评估是否需要 JSON 配置。

### 暂不做

- 不换基类（`LlamaEntity` → `PathAwareEntity`）：改动大，会牵动商队、骑乘、繁殖与口水弹射物的现有语义，等第 1~3 步稳定后再评估。
- 不改两个 Mixin 的注入点：收益低于回归风险，升级时再按需处理。

## 四、触发条件与验证

- **触发拆分**：实体类超过 2500 行，或新增第二个 Boss 之前，必须完成第 1、2 步。
- **每步验证**：`.\gradlew.bat build` 通过；新增纯逻辑补单测；行为改动先改 SPEC，再改代码；进游戏按 SPEC 验收用例跑一遍（Boss 形态、四阶段、瞬移、音波、脱战恢复）。
- **判定标准**：拆分后行为不变——数值、阶段切换点、愤怒阈值、音效触发次数与拆分前一致。
