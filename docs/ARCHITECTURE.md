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
├── combat/                  纯数值
│   └── CombatProfile        阶段表（伤害/护甲/距离）
├── hearing/                 听觉与愤怒
│   └── AngerLedger          每个玩家的愤怒、配对窗口、限频、衰减（无 Minecraft 类型）
├── entity/
│   ├── YaoYongshouEntity    编排：状态、AI 装配、战斗流程
│   ├── YaoYongshouSpitEntity 口水（自有实体类型 hush:yao_spit）
│   ├── YaoArmorPiercingDamageSource 部分穿甲的伤害源
│   └── goal/                6 个 AI 目标，各自一个文件
├── visual/
│   └── EntityParticles      粒子表现（只依赖 ServerWorld 与实体）
├── mixin/                   PlayerSoundMixin、LivingEntityArmorMixin
└── registry/                ModEntities / ModItems / ModSounds
```

## 3. 状态所有权

| 状态 | 持有者 | 说明 |
| --- | --- | --- |
| `bossForm` / `bossFormDormant` / `phase` | 实体 | 形态与阶段，服务端计算；NBT 只存前两个，阶段读档重算 |
| 愤怒、音效配对、限频 | `AngerLedger` | 有单测；离线玩家条目定期清理 |
| 声波节拍 | 实体 | `sonicTicks` 与 `lockSonicTicks` 两套节拍仍留在实体，待后续抽成 `SonicCycle`（有 `playSound` 副作用，需先做验收） |
| 好奇视线 | 实体 | `curiousPos` / `curiousUntil`，读档重置（见 SPEC 已知限制） |
| 粒子表现 | `EntityParticles` | 只读实体状态，不回写 |
| 挥击动画 | 实体 `ATTACK_START_AGE` | 唯一的 `TrackedData`，客户端按 `age` 插值 |

## 4. 本次拆分状态

| 阶段 | 内容 | 状态 | 验收 |
| --- | --- | --- | --- |
| 一 | AI Goal 拆到 `entity/goal`，账本、粒子各自成类 | 已完成 | `build` 通过；21 项单测（9 + 12）；实体 1990 → 1751 行 |
| 二 | 换基类（`LlamaEntity` → `PathAwareEntity`） | 未开始 | 需要游戏内存档兼容验证，不在地面改 |
| 三 | 口水去掉 `LlamaSpitEntity` 继承，抄写原版弹道与渲染 | 未开始 | 需要游戏内验证弹道与命中，先注册自有类型（已完成） |
| 四 | 剩余硬编码数值并入 `CombatProfile` | 未开始 | 数值表与 SPEC 同步 |

## 5. 不做的事

- 不为拆分而拆分：小于 40 行、只有一个调用点的逻辑不独立成类。
- 不改变可见行为：拆分前后伤害、阶段阈值、音效次数、粒子数量必须一致。
- 不顺手改数值：数值变更走 SPEC 流程，单独提交。
