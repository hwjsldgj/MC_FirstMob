# 架构

本文件描述包职责、依赖方向与拆分状态。行为与数值仍以 [SPEC](SPEC.md) 为准，原版依赖取舍见 [COUPLING](COUPLING.md)。

## 1. 原则

1. **单一职责**：一个类只回答一个问题；实体类只做「编排 + 状态持有」，不实现可以独立测试的规则。
2. **依赖单向**：`client → registry → entity → combat / hearing / visual / mixin`；底层包不得反向引用实体。
3. **纯逻辑可单测**：不触碰 Minecraft 类型的逻辑放独立类，用 JUnit 覆盖；触碰原版的逻辑靠构建 + 游戏内验收。
4. **一步一提交**：每次只搬一块，构建通过、行为不变再进入下一块。

## 2. 包结构

```
com.example.hush
├── Hush                     入口：注册三件套 + 创造栏
├── combat/                  纯数值与纯几何
│   ├── CombatProfile        阶段表（阈值/护甲/伤害/距离/节奏）
│   ├── GazeWatch            「玩家是否注视目标」的角度/距离判定
│   ├── SonicCycle           声波蓄力/间隔节拍（Boss 与阶段 1 各一个实例）
│   └── AttackBand           距离 → 攻击档（近战/口水/随机/声波/只追）
├── hearing/                 听觉与愤怒
│   └── AngerLedger          每个玩家的愤怒、配对窗口、限频、衰减（无 Minecraft 类型）
├── entity/
│   ├── YaoYongshouEntity    编排：状态、AI 装配、战斗流程
│   ├── YaoYongshouSpitEntity 口水（自有实体类型 hush:yao_spit）
│   ├── YaoArmorPiercingDamageSource 部分穿甲的伤害源
│   ├── TeleportSearch       传送落点搜索（3×3×4 空地判定）
│   └── goal/                6 个 AI 目标，各自一个文件
├── visual/
│   ├── EntityParticles      粒子表现（只依赖 ServerWorld 与实体）
│   └── BossBarView          Boss 血条（追踪玩家、节流、阶段 4 样式）
├── mixin/                   PlayerSoundMixin、LivingEntityArmorMixin
└── registry/                ModEntities / ModItems / ModSounds
```

## 3. 状态所有权

| 状态 | 持有者 | 说明 |
| --- | --- | --- |
| `bossForm` / `bossFormDormant` / `phase` | 实体 | 形态与阶段，服务端计算；NBT 只存前两个，阶段读档重算 |
| 愤怒、音效配对、限频 | `AngerLedger` | 有单测；离线玩家条目定期清理 |
| 声波节拍 | `SonicCycle` | 实体持有两个实例（Boss 阶段 / 阶段 1 各一个）；蓄力与开炮音效仍由实体触发，节拍本身有单测 |
| 好奇视线 | 实体 | `curiousPos` / `curiousUntil`，读档重置（见 SPEC 已知限制） |
| 粒子表现 | `EntityParticles` | 只读实体状态，不回写 |
| Boss 血条 | `BossBarView` | 只发给正在追踪的玩家；10 tick 节流；阶段 4 紫色「（狂暴）」 |
| 挥击动画 | 实体 `ATTACK_START_AGE` | 唯一的 `TrackedData`，客户端按 `age` 插值 |

## 4. 本次拆分状态

| 阶段 | 内容 | 状态 | 验收 |
| --- | --- | --- | --- |
| 一 | AI Goal 拆到 `entity/goal`，账本、粒子各自成类 | 已完成 | `build` 通过；21 项单测（9 + 12）；实体 1990 → 约 1600 行 |
| 二 | 声波节拍抽成 `combat/SonicCycle`，传送落点搜索抽成 `entity/TeleportSearch` | 已完成 | `build` 通过；31 项单测（9 + 12 + 5 + 5）；实体再减约 50 行 |
| 三 | 距离 → 攻击档抽成 `combat/AttackBand`，实体只负责执行 | 已完成 | `build` 通过；36 项单测；实体再减约 30 行 |
| 四 | Boss 血条抽成 `visual/BossBarView`（追踪/隐藏/刷新归它管） | 已完成 | `build` 通过；36 项单测；实体再减约 25 行 |
| 五 | 换基类（`LlamaEntity` → `PathAwareEntity`） | 未开始 | 需要游戏内存档兼容验证，不在地面改 |
| 六 | 口水去掉 `LlamaSpitEntity` 继承，抄写原版弹道与渲染 | 未开始 | 需要游戏内验证弹道与命中，先注册自有类型（已完成） |
| 七 | 战斗相关硬编码数值并入 `CombatProfile` | 已完成 | 单测 37 条；听觉/空闲/传送常数属于各自域，保留在实体 |

## 5. 不做的事

- 不为拆分而拆分：小于 40 行、只有一个调用点的逻辑不独立成类。
- 不改变可见行为：拆分前后伤害、阶段阈值、音效次数、粒子数量必须一致。
- 不顺手改数值：数值变更走 SPEC 流程，单独提交。

## 6. 测试覆盖与边界

| 层 | 自动覆盖 | 覆盖不到 |
| --- | --- | --- |
| JUnit（37 条） | `CombatProfile` 10、`AngerLedger` 12、`GazeWatch` 5、`SonicCycle` 5、`AttackBand` 5 | 任何 Minecraft 类型 |
| `smoke` 数据包 | 启动、Mixin 应用、实体注册、summon、口水实体 | 客户端 |
| `smoke` 假玩家（14 项） | 注视冻结日志与背对对照、注视静止、Boss 形态、阶段 2/3/4 护甲与体型、阶段 4 增益、开火日志、脱战回血 | 命中与伤害数值、穿甲结算、血条/音效/粒子 |

当前 `YaoYongshouEntity` 1680 行。穿甲链路（`YaoArmorPiercingDamageSource` + `LivingEntityArmorMixin`）没有自动断言——假玩家是创造模式且带抗性，不吃伤害；改动后必须进游戏手工验。
