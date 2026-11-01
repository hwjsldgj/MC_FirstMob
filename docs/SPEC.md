# 行为规范（SPEC）

> 本文是姚永寿行为的**唯一事实来源**：代码常量、README 的描述与本文不一致时，先确认哪边是当前设计，再让两边一致。
> 注册 ID、Java 接口与资源契约见 [API.md](API.md)；面向玩家的简介见 [README](../README.md)；工程约定见 [AGENTS](../AGENTS.md)。

## 1. 术语

| 术语 | 含义 |
| --- | --- |
| 真噪音 | 玩家发出的、被听觉接收的声音；大噪音 +12，脚步/落地/受击 +5（每类 5 秒最多 1 次） |
| 愤怒 | 每玩家 0~100，每 20 tick -1；30 警觉、100 激怒 |
| 好奇 | 冷静时听到声音后转向声源 40 tick，不锁玩家 |
| 锁定 | `setTarget(player)`，目标是一名玩家 |
| 激怒 / 声波模式 | `sonicMode == true`，愤怒 100 时进入，愤怒 <90 或目标出 40 格退出 |
| 普通模式 | 未进入声波模式 |

1 tick = 1/20 秒。

## 2. 实体基线

| 项 | 值 |
| --- | --- |
| 实体 ID | `firstmob:yao_yongshou` |
| Java 基类 | `LlamaEntity`（只沿用普通寻路 + 吐口水） |
| 外形 | 僵尸 `BipedEntityModel`（`EntityModelLayers.ZOMBIE`）+ `minecraft:textures/entity/zombie/zombie.png`；待机双臂前伸复用原版 `CrossbowPosing#meleeAttack` |
| 近战动画 | 命中时记录 `ATTACK_START_AGE`（10 tick = 0.5 秒）；模型用渲染端 `age + tickDelta` 算进度，逐帧插值：快速抬到头顶 → 约 0.12 秒砸下 → 回位 |
| 渲染与实体分离 | 外形不继承任何原版生物实体；模型只影响观感 |
| 碰撞箱 | 宽 0.6 × 高 1.95，眼高 1.62（`EntityType` 基础值；`GENERIC_SCALE = 2` 后实际 1.2 × 3.9） |
| 生成组 | `SpawnGroup.MISC` |
| 追踪范围 | 10（`EntityType.Builder#maxTrackingRange`） |
| 属性 | 原版羊驼属性 + `GENERIC_MAX_HEALTH = 100` + `GENERIC_SCALE = 1`（Boss 形态改 2）+ `GENERIC_ARMOR = 5`（Boss 形态 20、Boss 且 <20% 血 10）+ `GENERIC_ARMOR_TOUGHNESS = 0`（Boss 且 <20% 血 10）+ `GENERIC_ATTACK_DAMAGE = 15` + `GENERIC_ATTACK_KNOCKBACK = 0` |
| 血量 | 100 点（羊驼默认 15~30）；生成时即满血 |
| 形态 | 生成时普通形态，血量掉到 80% 以下转 Boss 形态；解除锁定后 30 秒没有目标会退回普通形态，重新获得目标再变身；低于 20% 触发濒死效果；见 [§3.3](#33-形态普通--boss--濒死) |
| 体型 | 普通 1 倍；Boss 形态 2 倍（运行时改 `generic.scale`，碰撞箱、眼高与模型一起放大） |
| 护甲 | 普通 5 点；Boss 形态 20 点（原版上限 30，约减伤 80%）；Boss 且 <20% 血时改为 10 点护甲 + 5 点护甲韧性，回到 20% 以上立刻改回 20/0 |
| Boss 血条 | 仅 Boss 形态显示：屏幕顶部红色进度条，标题「姚永寿」，每 10 tick 同步一次血量，死亡当 tick 收起；锁定时变紫、标题加「（狂暴）」 |
| 残血锁定 | 血量 < 60% 起死咬玩家：5 格内近战、5~16 格口水、17~40 格声波（32 格以外边追边放），满血且 30 秒无玩家伤害才解除；见 [§3.4](#34-残血锁定60) |
| 常驻 | `setPersistent()`：不因玩家远离被自然清除，只能被打死或指令移除 |
| 掉落经验 | 500 点（`getXpToDrop`，由击杀者吸收） |
| 幼年形态 | 不存在：`isBaby()` 恒 false、`getBreedingAge()` 恒 0 |
| 繁殖 / 喂食 / 驯服 / 骑乘 | 全部禁用 |
| 同类关系 | 不索敌同类，口水也不伤害同类 |

## 3. 状态机

### 3.1 状态

| 状态 | 判定条件 | 含义 |
| --- | --- | --- |
| `IDLE` | 无目标 | 游荡、看玩家 |
| `CURIOUS` | 冷静 + `curiousUntil` 未到 | 只转头看声源 |
| `ALERT` | 目标玩家 + 愤怒 `30~99` | 口水追击，25 格放弃 |
| `REVENGE` | `revengeMode && !sonicMode` | 挨打后的有限反击 |
| `SONIC` | `sonicMode` | 监守者声波，愤怒 `<90`、出 40 格或死亡才停 |

### 3.2 转移

| # | 从 | 触发 | 处理 | 到 |
| --- | --- | --- | --- | --- |
| T1 | 任意（非 SONIC） | 听到声音 | 按大/小噪音加愤怒，冷静时 `triggerCuriosity` | `CURIOUS` / 原状态 |
| T2 | 任意（非 SONIC） | 某玩家愤怒 ≥30 | 锁 prime suspect | `ALERT` |
| T3 | `ALERT` | 某玩家愤怒 =100 且距离 ≤40 | `enterSonicMode` | `SONIC` |
| T4 | `ALERT` | 愤怒 <30 | 清目标 | `IDLE` |
| T5 | 任意 | 当前目标死亡 | 当 tick 清目标及所有攻击状态 | `IDLE` |
| T6 | 任意（非 SONIC） | 被非同类生物/玩家打中 | 置 `revengeShots = 3`、`revengeDeadline = now + 100`、`setTarget(attacker)` | `REVENGE` |
| T7 | `REVENGE` | 打满 3 次 | `setAttacker(null)`、`setTarget(null)` | `IDLE` |
| T8 | `REVENGE` | 5 秒内一次都没打出去 | 同 T7 | `IDLE` |
| T9 | `SONIC` | 距离 >40、目标死亡/下线、或愤怒 <90 | `stopSonicMode`；仍 ≥30 则回 `ALERT`，否则 `IDLE` | `ALERT` / `IDLE` |
| T10 | `SONIC` | 被**另一个玩家**打中 | `sonicTarget` 换成他、其愤怒置 100、`sonicTicks = 0` | `SONIC` |
| T11 | `SONIC` | 被非玩家生物打中 | `stopSonicMode` 后按 T6 进入反击 | `REVENGE` |

补充：

- T1 的冷静好奇有 40 tick 冷却；目标出现、进入声波或锁定时立即停止好奇。
- `ALERT` 追出 25 格放弃；`SONIC` 追到 40 格。
- 锁定模式（<60%）接管后不再执行 T2/T3/T4；Boss 形态不再听声音，直接硬锁当前/最近玩家，目标不受 25/40 距离放弃，40 格内声波、之外追击。
- 反击期间愤怒仍在累计，但目标优先给攻击者；进入声波时清反击。
- T6 的"被打中"不要求伤害来源是玩家，但必须 `LivingEntity` 且非同类。

### 3.3 形态（普通 ⇄ Boss → 濒死）

形态与战斗状态机正交：主要看血量比例，但 Boss 形态可以退回普通形态。

| 形态 | 进入条件 | 属性 / 表现 |
| --- | --- | --- |
| 普通 | 生成时；或从 Boss 形态退回 | 体型 1 倍、护甲 5、护甲韧性 0、无 Boss 血条 |
| Boss | 血量 < 80% 最大生命（首次；退回后需**重新获得目标**） | 体型 2 倍、护甲 20、护甲韧性 0、显示红色 Boss 血条，并获得**永久**速度 I、力量 I、**抗性提升 I**；进入当 tick 释放一次 `dust_color_transition` 变身粒子（§3.6） |
| 濒死 | 血量 < 20% 最大生命，且本次是"从 ≥20% 掉到 <20%" | Boss 形态下护甲改为 10、护甲韧性改为 5；立即获得附魔金苹果效果，每次下穿阈值触发一次；触发当 tick 释放一次 `totem_of_undying` 粒子（§3.6） |

- Boss 形态**可以退回普通形态**：解除锁定后连续 30 秒（600 tick）没有目标（`getTarget() == null`），退回时体型恢复 1 倍、护甲 5、韧性 0，移除力量与抗性提升，收起 Boss 血条；速度由 `updateMovementSpeed` 下一 tick 接管。
- 锁定模式期间**不会**退回（即使血量已回到 60% 以上）：必须先按 §3.4 解除锁定，再连续 30 秒没有目标才会退回。
- 退回后血量通常仍 < 80%，此时保持普通形态；**重新获得目标**（被打、被噪音激怒、进入锁定）会立刻再次变身，并重新播放变身粒子。
- 回血到 ≥ 80% 会清除"已退回"标记（`bossFormDormant`），下次掉到 80% 以下仍照常从普通形态变身。
- 抗性提升 I 是 Boss 形态的常驻增益（无限时长、无粒子）；退回普通形态时移除。濒死金苹果的抗性提升只有 5 分钟，且退回只发生在 ≥60% 血时，两者不会互相干扰。
- 护甲/韧性每 tick 按形态与血量比例重算：普通 5/0；Boss 20/0；Boss 且 < 20% 血 10/5。高于 20% 后立即改回 20/0。
- 脱战回血：玩家连续 10 秒（200 tick）没有对姚永寿造成伤害，且未满血时，它获得生命恢复 I（无限时长、无粒子），直到回满；再次被玩家打中或回满后立即移除（不会误删金苹果的恢复 II）。
- 羊驼的 `initAttributes` 会在生成时把最大生命随机成 15~30，覆盖 `ModEntities` 里的 100；姚永寿把它覆盖成空实现，保证固定 100 血，否则 80% / 60% / 20% 阈值全部失真。
- 阈值用 `getHealth() / getMaxHealth()` 计算，每 tick 在 `tick()` 里判定，最多延迟 1 tick。
- 濒死效果合并 1.8 老版与 1.9+ 现版两套：伤害吸收 II（2 分钟）、生命恢复 II（30 秒，取老版更长的时长）、抗性提升（5 分钟）、抗火（5 分钟）。
- `bossForm` / `bossFormDormant` / `lowHealthTriggered` 写入 NBT：重载后不会丢掉 Boss 形态，也不会把"已退回普通形态"读成未变身。
- 濒死触发后若回血越过 20%、再掉回 20% 以下，会再次触发（`lowHealthTriggered` 随回血复位）。

### 3.4 残血锁定（<60%）

血量低于 60% 时进入锁定模式：不再按声音/距离规则脱战，而是死咬玩家。

| 项 | 规则 |
| --- | --- |
| 进入 | `getHealth() < 60% × maxHealth`（每 tick 判定；进入时清空好奇/声波/反击状态） |
| 目标 | 优先最近一次打它的玩家；没有则最近的存活玩家（不限距离） |
| 攻击 | 距离 < 5 格：只近战（比普通模式的 3 格更早贴脸）；5~16 格：吐口水；17~32 格：站定蓄力放声波；超过 32 格到 40 格：边追边放声波；> 40 格只追不打 |
| 移速 | 追玩家时全程挂速度 II（锁定模式与普通追击都一样）：进入近战距离就收回，退回 Boss 形态的永久速度 I |
| 解锁 | 自己满血 **且** 30 秒（600 tick）内没有玩家对它造成伤害，两条同时满足才解除 |
| 目标死亡 | 该玩家锁定结束，当 tick 改锁最近的存活玩家；没有玩家就停手等待（仍保持锁定状态） |
| 声音 | 锁定期间 `hearVibrationFrom` / `hearSupplement` 直接忽略，不会改动目标 |
| 其他生物 | 被打时不换目标；不进入反击计数，也不受 3 次还手上限约束 |
| 狂暴表现 | Boss 血条变紫、标题加「（狂暴）」；不改攻速/伤害 |

- 锁定期间不执行"好奇"、"警觉口水"、"25 格放弃"、"反击 5 秒超时"，也不进入普通激怒声波。
- 锁定声波节拍：蓄力 34 tick、循环 160 tick；`<20%` 血时按 80% 计算（27 tick / 128 tick）。
- 吐口水冷却同样参与 `<20%` 的 20% 缩短（34 → 27 tick），两种模式共用一套冷却记录。
- 速度 II 只在锁定模式追击时生效；进入 5 格近战距离就退回速度 I。
- 声波蓄力一旦开始就先打完这一发：蓄力期间不切近战/口水，目标进入 5~16 格或跑出 40 格也照常结算。
- 护甲 20 + 抗性提升 I 让 100 血非常耐打；锁定是"状态"而非"一次事件"，回血到 60% 以上也不会自动解除。

### 3.5 空闲跟随与传送

没有目标、也没有进入锁定时（`IDLE`），姚永寿围着最近的存活玩家游走，**始终保持 ≥16 格站距**：

| 距离 | 行为 |
| --- | --- |
| ≤ 20 格 | 每 tick `lookAt` 玩家（头部一直盯着看；`CalmLookAtEntityGoal` 只有 8 格，覆盖不到这么远；好奇期间让位给声源） |
| 16~32 格 | **站定不动**，只盯着玩家（`IdleWanderGoal` 会拦掉原版游走） |
| > 32 格 | `navigation.startMovingTo(player, 1.0)` 靠近，直到回到 32 格内 |
| < 16 格 | 沿"玩家 → 自己"方向后退到 16 格以外（玩家主动贴上来也不会被贴脸） |
| > 48 格 | 传送一次：方向相对玩家朝向随机偏 **35°~145°**（左右随机，永远避开正前方），半径随机 **18~22 格**（约 20 格）；最多试 6 次 |

- 落点要求 **3×3×4** 的空地：3×3 脚下实心 + 3×3 各 4 格空气，且区块已加载。
- 玩家周围找不到这样的落点：退而求其次，随机方向传送到距玩家 **48~64 格**的安全点（不打扰玩家），之后 **每 5 秒（100 tick）** 再检测一次。
- 传送会停掉寻路并清零速度；传送后短暂冷却，避免同一 tick 反复触发。
- 只对**最近的存活玩家**生效；旁观者不参与，玩家死亡/下线时自动跟随下一个最近玩家。
- 只在 `IDLE` 生效：进入噪音追击、反击或锁定后不传送。
- 移动速度：**游走时速度 III**；战斗追击速度 II；其余按 Boss 形态速度 I / 普通形态无加速（见 §3.4）。

### 3.6 粒子效果（纯表现）

四类粒子都只在服务端生成（`ServerWorld#spawnParticles`），由服务端广播给追踪者；全部使用 `minecraft:` 原版粒子，不新增注册项或资源文件。

| 触发 | 粒子 | 表现 |
| --- | --- | --- |
| 转入 Boss 形态（< 80%，一次性） | `minecraft:dust_color_transition` | 身体中心炸开一团尘，颜色从熔岩橙过渡到暗紫（`scale = 1.5`），`BOSS_FORM_PARTICLE_COUNT = 80` 颗 |
| 濒死效果结算（< 20%，每次下穿阈值） | `minecraft:totem_of_undying` | 身体中心炸开不死图腾粒子，`LOW_HEALTH_PARTICLE_COUNT = 100` 颗 |
| 持有速度效果（I / II / III）且在地面水平移动 | `minecraft:firework` | 脚下每 `SPEED_TRAIL_PARTICLE_INTERVAL_TICKS = 4` tick 撒 2 颗；站着不动不触发 |
| 近战命中 | `minecraft:dust_pillar` | 命中目标脚下升起尘柱，`MELEE_DUST_PARTICLE_COUNT = 6` 颗；贴图取目标脚下方块，空气/流体时退回石头 |

- 变身与濒死只在该事件结算的当 tick 释放一次，不会因为持续低血而反复播放。
- 速度拖尾只判是否持有速度效果（不限等级），因此空闲游走的常驻速度 III、Boss 形态的永久速度 I 与指令给的速度都生效。
- `dust_pillar` 必须附带非空气、非流体的方块状态，否则客户端取不到粒子贴图。

## 4. 听觉与愤怒

### 4.1 声音来源

| 来源 | 注入点 | 说明 |
| --- | --- | --- |
| 原版振动（主） | 实体实现 `Vibrations`，接收区块段 `GameEvent` 分发器投递的事件 | 只收 `WARDEN_CAN_LISTEN`、发射者为玩家；20 格 |
| 玩家音效（补充） | `PlayerEntity#playSound(SoundEvent, float, float)` 的 HEAD（`PlayerSoundMixin`） | 只收"同一动作没有 GameEvent"的声音；2 tick 配对，事件先到/后到都能去重 |

- 振动按距离延迟（约 1 格 = 1 tick）后才回调；`accepts` 在派发同 tick 先记标记，音效补充靠这个标记配对。
- 挖方块/交互不再单独挂 mixin，统一由 `GameEvent` 触发。
- 丢弃：客户端世界、玩家死亡、旁观者、音效 ID 以 `.death` 结尾；怪物、爆炸、发射器不算。

### 4.2 分类与愤怒

| 来源 | 类别 | 愤怒 |
| --- | --- | --- |
| `STEP` | 小噪音 | +5 |
| `HIT_GROUND` | 小噪音 | +5 |
| `ENTITY_DAMAGE` | 小噪音 | +5 |
| 其余 `GameEvent` | 大噪音 | +12 |
| 未记录音效 | 大噪音 | +12 |

- 小噪音每玩家每类 5 秒最多 1 次；大噪音不限。
- 玩家被玩家打中也会 +12（借用监守者 `AngerManager` 的受伤仇恨）。

### 4.3 愤怒值

- 每玩家 0~100；超过 100 封顶。
- 每 20 tick 全体 -1，归零移除。
- 档位：`<30` 冷静；`30~99` 警觉；`100` 激怒。
- 声波有回差：进入 100，退出 `<90`，避免刚开一炮就掉档。
- prime suspect：愤怒最高的存活玩家；同分取最近。

### 4.4 好奇

- 冷静档听到任何噪音（含脚步/落地/受击）：转向声源 **40 tick**，期间不注视玩家、不锁目标。
- 40 tick 冷却；目标出现、进入声波或锁定时立即停止好奇。
- 好奇只转头，不改变寻路。

### 4.5 档位行为

| 状态 | 行为 |
| --- | --- |
| 冷静 | 好奇；愤怒 `<30` 时清空声音目标 |
| 警觉 | 锁 prime suspect，25 格内追击、吐口水 |
| 激怒 | 声波替代口水；40 格内追打，目标出 40 格或愤怒 `<80` 退出 |
| Boss 形态 | 完全不听声音；当前/最近玩家始终锁定；40 格内声波，之外继续追击 |
| 锁定 | 狂暴，只改血条颜色/标题，不改攻速/伤害；锁定逻辑照旧 |

### 4.6 遮挡与潜行

- 羊毛：振动与音效补充都按 `BlockTags.OCCLUDES_VIBRATION_SIGNALS` 遮挡。
- 潜行：音效补充关闭；振动按 `IGNORE_VIBRATIONS_SNEAKING` 过滤。
- 旁观者、死亡玩家、客户端世界一律忽略。

### 4.7 与旧 3/9 的对应

- 单次大噪音 +12、每 20 tick -1：3 次≈31~36（警觉），9 次≈100（激怒）。
- 小噪音 +5，防止走路自动激怒。

## 5. 攻击

### 5.1 Goal 优先级

`initGoals()` 先 `super.initGoals()`，随后 `goalSelector` 与 `targetSelector` **全部清空**，再按表重建：

| 优先级 | Goal | 参数 | 职责 |
| --- | --- | --- | --- |
| 0 | `SwimGoal` | — | 浮水 |
| 1 | `CuriousLookGoal` | — | 冷静好奇时看向声源 |
| 2 | `CloseMeleeGoal`（`MeleeAttackGoal` 子类） | 速度 1.2，`pauseWhenMobIdle = true` | 3 格内近战 |
| 3 | `ProjectileAttackGoal` | 速度 1.25，间隔 34 tick，射程 20 | 走近并吐口水 |
| 6 | `IdleWanderGoal`（`WanderAroundFarGoal` 子类） | 速度 1.0 | 游荡；玩家在 16~20 格的"合适距离"内时 `canStart` / `shouldContinue` 都返回 false，站定只看人 |
| 7 | `CalmLookAtEntityGoal`（`LookAtEntityGoal` 子类） | 玩家，8 格 | 看人；好奇期间让位给声源 |
| 8 | `CalmLookAroundGoal`（`LookAroundGoal` 子类） | — | 随机张望；好奇期间让位 |

`targetSelector` 保持为空：原版 `SpitRevengeGoal` 会在目标被清空后重新锁定上次攻击者，反击因此由 `damage()` 自行接管。

### 5.2 远程口水

- 进入条件：目标存在、非声波模式、距离 > 3 格。
- 首发：`setTarget` 成功锁定新目标的瞬间，若距离 ≤ 20 格且 `canSee`，立即 `shootAt` 一次（仍受 3/5 格限制），并记一次冷却；被激怒时同样适用。
- 守卫：客户端、`sonicMode`、距离 ≤ 3 格（锁定模式 5 格），任一成立直接返回，不生成弹体；锁定模式的冷却另由 `nextSpitTick` 控制。
- 弹体：复用 `EntityType.LLAMA_SPIT`，实体类 `YaoYongshouSpitEntity`。
- 弹道：出膛点 `(x, eyeY - 0.1, z)`；速度 2.5，散布 0.4；竖直方向补偿 `drop = 0.5 × 0.06 × t²`，其中 `t = 水平距离 / 2.5`。
- 音效：`firstmob:entity.yao_yongshou.spit`（`SoundCategory.NEUTRAL`，音量/音调 1.0）。
- 每次发射都会调用 `countRevengeShot()`。

口水命中（`YaoYongshouSpitEntity#onEntityHit`）：

| 项 | 值 |
| --- | --- |
| 伤害 | 4（`firstmob:yao_spit`） |
| 伤害类型 | `scaling: never`、`exhaustion: 0.1`，和平难度也生效 |
| 击退 | 0.9，方向为弹体水平速度的反方向（`takeKnockback` 内部再取反） |
| 无效目标 | 发射者本人、`YaoYongshouEntity`、非 `LivingEntity` |
| 击退时机 | 无论伤害是否生效都击退（创造模式只被击退） |

### 5.3 近战

- `CloseMeleeGoal`，优先级 2，只在 3 格内接管；`canStart` 与 `shouldContinue` **都**卡 3 格，目标退出后立刻把控制权还给远程。
- 近战伤害 15（对齐铁傀儡 `GENERIC_ATTACK_DAMAGE`），伤害类型 `firstmob:yao_melee`（`scaling: never`，**和平难度同样生效**，护甲/抗性/盾牌照常结算）；`GENERIC_ATTACK_KNOCKBACK = 0`，命中后额外把目标向上顶 `0.4 × max(0, 1 - 击退抗性)`，与铁傀儡的击退一致（没有水平加成）。
- 挥击动画与 `dust_pillar` 按**挥击**触发，不要求伤害结算成功：创造模式等免疫目标时只播放动作与粒子、不结算伤害（调试时也能看到近战了）。
- `tryAttack` 命中后扣反击计数。
- 声波模式期间近战**照常**可用（只有远程被禁）。

### 5.4 挨打反击

- 触发：`damage()` 结算成功后，攻击者为 `LivingEntity`、非自身、非同类。
- 状态：`revengeMode = true`、`revengeShots = 3`、`revengeDeadline = now + 100`、`setTarget(attacker)`。
- 扣减：每次 `shootAt` 发射、每次 `tryAttack` 命中各扣 1，并刷新 deadline。
- 结束：扣到 0（T7）或超时（T8）时清空 attacker 与 target。
- 进入激怒/声波会清掉 `revengeMode`，避免反击计数打断新的战斗。
- 反击期间不受"愤怒回落"影响，但仍受 25 格放弃（玩家目标）约束。
- 例外：声波模式下被**锁定目标本人**打中不会触发反击（保持声波锁定）；被另一个玩家打中则切换 `sonicTarget` 并把他的愤怒拉到 100，声波不中断。

### 5.5 声波（监守者）

触发：某玩家愤怒达到 **100** 且距离 ≤ 40 格；`sonicMode` 为 true 时不重复进入。退出条件：目标为空/死亡/下线、距离 > 40 格，或该玩家愤怒 `<90`（回差）。

节拍（`sonicTicks` 从 0 开始，每 tick +1）：

| tick | 行为 |
| --- | --- |
| 每 tick | `faceTarget`，随后 `sonicTicks++` |
| 0 | 播放蓄力音 `firstmob:entity.yao_yongshou.sonic_charge` |
| 34 | 若与目标距离 > 3 格，`fireSonicBoom` |
| 160 | `sonicTicks` 归零，进入下一轮（再次蓄力） |

- 激怒后**替代口水**：除贴身近战外不再吐口水。
- 没到 100 时（警觉）无论距离多远都只吐口水（超过 25 格会放弃追击）。
- 目标跑出 40 格退出声波模式。
- 每 tick 按 UUID 重新解析目标并 `setTarget`，中途被其他生物抢仇恨不影响声波锁定。
- Boss 形态不再听声音，当前/最近玩家始终锁定：40 格内声波，之外继续追击。

开炮（`fireSonicBoom`）：

| 项 | 值 |
| --- | --- |
| 起点 | 实体位置 + `height × 0.6` 的高度（胸口） |
| 终点 | 目标眼位 |
| 粒子 | `ParticleTypes.SONIC_BOOM`，沿射线每格 1 个，步数 `floor(距离) + 7` |
| 音效 | `firstmob:entity.yao_yongshou.sonic_boom`，音量/音调 3.0/1.0 |
| 伤害 | 10，`minecraft:sonic_boom`（`scaling: always`），只结算锁定目标 |
| 击退 | 水平 `2.5 × (1 - 击退抗性)`，竖直 `0.5 × (1 - 击退抗性)`，**仅当伤害结算成功** |

## 6. 数值总表

| 常量 | 值 | 单位 | 含义 |
| --- | --- | --- | --- |
| `HEARING_RANGE` | 20 | 格 | 听觉半径 |
| `MAX_CHASE_RANGE` | 25 | 格 | 警觉模式放弃距离（激怒/声波 40 格） |
| `MINOR_NOISE_COOLDOWN_TICKS` | 100 | tick | 脚步/落地/受击每类限频（5 秒） |
| `ANGER_MAX` | 100 | 点 | 愤怒上限 |
| `ANGER_AGITATED` | 30 | 点 | 警觉（口水）阈值 |
| `ANGER_ANGRY` | 100 | 点 | 激怒（声波）阈值 |
| `ANGER_ANGRY_EXIT` | 90 | 点 | 声波退出阈值（回差） |
| `ANGER_PER_LOUD_NOISE` | 12 | 点 | 单次大噪音愤怒 |
| `ANGER_PER_MINOR_NOISE` | 5 | 点 | 单次小噪音愤怒 |
| `ANGER_DECAY_INTERVAL_TICKS` | 20 | tick | 每 20 tick 全体愤怒 -1 |
| `CURIOUS_DURATION_TICKS` | 40 | tick | 好奇看声源时长/冷却 |
| `SUPPLEMENT_PAIR_TICKS` | 2 | tick | 音效与 GameEvent 配对窗口 |
| `SPIT_COOLDOWN_TICKS` | 34 | tick | 吐口水间隔（原版 40） |
| `SPIT_RANGE` | 20 | 格 | 吐口水射程 |
| `MELEE_RANGE_SQUARED` | 9 | 格² | 3 格近战接管 / 远程禁用距离 |
| `ATTACK_DAMAGE` | 15 | 点 | 近战伤害（铁傀儡数值） |
| `ATTACK_KNOCKBACK` | 0 | — | 近战击退属性（铁傀儡没有加成） |
| `GOLEM_KNOCKUP` | 0.4 | 倍 | 命中后向上顶的速度（× (1 - 击退抗性)） |
| `REVENGE_SHOT_LIMIT` | 3 | 次 | 每次挨打最多还手次数 |
| `REVENGE_TIMEOUT_TICKS` | 100 | tick | 反击超时 |
| `SONIC_RANGE` | 40 | 格 | 声波/激怒追击距离 |
| `SONIC_CHARGE_TICKS` | 34 | tick | 蓄力时长 |
| `SONIC_INTERVAL_TICKS` | 160 | tick | 声波循环长度（8 秒） |
| `SONIC_DAMAGE` | 10 | 点 | 声波伤害 |
| `BOSS_MAX_HEALTH` | 100 | 点 | 最大生命（`ModEntities` 中的属性基值，两种形态相同） |
| `BOSS_SCALE` | 2 | 倍 | Boss 形态体型缩放（运行时写入 `generic.scale`；普通形态 1） |
| `NORMAL_SCALE` | 1 | 倍 | 普通形态体型（生成时与退回时写入） |
| `NORMAL_ARMOR` | 5 | 点 | 普通形态护甲（生成时与退回时写入） |
| `NORMAL_ARMOR_TOUGHNESS` | 0 | 点 | 普通形态护甲韧性 |
| `BOSS_ARMOR` | 20 | 点 | Boss 形态护甲（运行时写入 `generic.armor`） |
| `BOSS_ARMOR_TOUGHNESS` | 0 | 点 | Boss 形态基础护甲韧性 |
| `LOW_HEALTH_ARMOR` | 10 | 点 | Boss 形态且 <20% 血时的护甲 |
| `LOW_HEALTH_ARMOR_TOUGHNESS` | 5 | 点 | Boss 形态且 <20% 血时的护甲韧性 |
| `OUT_OF_COMBAT_REGEN_TICKS` | 200 | tick | 玩家多久没造成伤害就触发脱战回血（10 秒） |
| `OUT_OF_COMBAT_REGEN_AMPLIFIER` | 0 | 等级 | 脱战回血的生命恢复等级（I） |
| `BOSS_FORM_HEALTH_RATIO` | 0.8 | 比例 | 转入 Boss 形态的血量阈值；退回后重新获得目标时再次生效 |
| `BOSS_FORM_REVERT_TICKS` | 600 | tick | 解除锁定后连续无目标多久退回普通形态（30 秒） |
| `LOW_HEALTH_RATIO` | 0.2 | 比例 | 触发附魔金苹果效果的阈值 |
| `LOW_HEALTH_ABSORPTION_TICKS` | 2400 | tick | 伤害吸收持续时间（2 分钟） |
| `LOW_HEALTH_REGEN_TICKS` | 600 | tick | 生命恢复 II 持续时间（30 秒，取 1.8 老版） |
| `LOW_HEALTH_RESISTANCE_TICKS` | 6000 | tick | 抗性提升 / 抗火持续时间（各 5 分钟） |
| `LOCK_HEALTH_RATIO` | 0.6 | 比例 | 进入残血锁定的血量阈值 |
| `LOCK_RELEASE_NO_DAMAGE_TICKS` | 600 | tick | 解锁所需的"无玩家伤害"时长（30 秒） |
| `LOCK_MELEE_RANGE_SQUARED` | 25 | 格² | 锁定模式的 5 格近战距离（普通模式仍 3 格） |
| `LOCK_SPIT_RANGE` | 16 | 格 | 锁定模式的口水射程（5~16 格） |
| `LOCK_SONIC_RANGE` | 32 | 格 | 锁定模式的站桩声波射程（17~32 格；32 格以外边追边放） |
| 移动速度 | 游走速度 III / 战斗追击速度 II / 常态 Boss 形态速度 I、普通形态无加速（由 `updateMovementSpeed` 每 tick 统一管理） |
| `IDLE_LOOK_RANGE_SQUARED` | 400 | 格² | 空闲时注视玩家的距离（20 格） |
| `IDLE_KEEP_DISTANCE` | 16 | 格 | 游走时与玩家保持的最小距离 |
| `IDLE_APPROACH_SQUARED` | 1024 | 格² | 超过 32 格才靠近；16~32 格站定注视 |
| `IDLE_TELEPORT_DISTANCE_SQUARED` | 2304 | 格² | 空闲传送阈值（> 48 格） |
| `IDLE_TELEPORT_RADIUS_MIN/MAX` | 18 / 22 | 格 | 传送半径随机区间（约 20 格） |
| `FALLBACK_TELEPORT_RADIUS_MIN/MAX` | 48 / 64 | 格 | 玩家周围无落点时的退路半径 |
| `TELEPORT_MIN/MAX_ANGLE_DEGREES` | 35 / 145 | 度 | 相对玩家朝向的随机偏角区间（0° 正前方被排除） |
| `TELEPORT_ATTEMPTS` | 6 | 次 | 每次传送最多试几个随机落点 |
| `TELEPORT_SPACE_RADIUS` / `HEIGHT` | 1 / 4 | 格 | 落点要求的 3×3×4 空间 |
| `TELEPORT_RETRY_TICKS` | 100 | tick | 找不到落点后的重试间隔（5 秒） |
| `TELEPORT_RETRY_TICKS` | 20 | tick | 传送失败后的重试间隔 |
| `ATTACK_BOOST_COOLDOWN_FACTOR` | 0.8 | 系数 | `<20%` 血时攻击冷却的倍率（34→27、160→128） |
| `BOSS_XP_REWARD` | 500 | 点 | 死亡掉落经验 |
| `BOSS_BAR_INTERVAL_TICKS` | 10 | tick | Boss 血条同步间隔 |
| `BOSS_FORM_PARTICLE_COUNT` | 80 | 颗 | Boss 变身 `dust_color_transition` 数量 |
| `BOSS_FORM_PARTICLE_SCALE` | 1.5 | — | Boss 变身尘粒的尺寸 |
| `LOW_HEALTH_PARTICLE_COUNT` | 100 | 颗 | 濒死触发 `totem_of_undying` 数量 |
| `SPEED_TRAIL_PARTICLE_INTERVAL_TICKS` | 4 | tick | 有速度效果时走路拖尾的间隔 |
| `SPEED_TRAIL_PARTICLE_COUNT` | 2 | 颗 | 每次拖尾的 `firework` 数量 |
| `MELEE_DUST_PARTICLE_COUNT` | 6 | 颗 | 近战命中 `dust_pillar` 数量 |

## 7. 音效与文本

| 事件 ID | 复用的原版事件 | 字幕键 | 中文文案 |
| --- | --- | --- | --- |
| `firstmob:entity.yao_yongshou.ambient` | `minecraft:entity.llama.ambient` | `subtitles.firstmob.entity.yao_yongshou.ambient` | 姚永寿：吼叫 |
| `firstmob:entity.yao_yongshou.hurt` | `minecraft:entity.llama.hurt` | `...hurt` | 姚永寿：受伤 |
| `firstmob:entity.yao_yongshou.death` | `minecraft:entity.llama.death` | `...death` | 姚永寿：死亡 |
| `firstmob:entity.yao_yongshou.spit` | `minecraft:entity.llama.spit` | `...spit` | 姚永寿：吐口水 |
| `firstmob:entity.yao_yongshou.sonic_charge` | `minecraft:entity.warden.sonic_charge` | `...sonic_charge` | 姚永寿：蓄力 |
| `firstmob:entity.yao_yongshou.sonic_boom` | `minecraft:entity.warden.sonic_boom` | `...sonic_boom` | 姚永寿：音波尖啸 |

死亡信息：`death.attack.yao_spit` → `%1$s 被 %2$s 的口水淹死了`（英文：`%1$s was drowned in %2$s's spit`）；`death.attack.yao_melee` → `%1$s 被 %2$s 一巴掌拍死了`（英文：`%1$s was smacked to death by %2$s`）。

## 8. 资源契约

文件级契约（字段含义、校验点）见 [API.md 的「资源与数据契约」](API.md#5-资源与数据契约)。摘要：

| 文件 | 作用 |
| --- | --- |
| `assets/firstmob/sounds.json` | 6 个自定义事件，全部以 `{"type": "event"}` 转发原版事件 |
| `assets/firstmob/lang/zh_cn.json` / `en_us.json` | 实体名、物品名、字幕、死亡信息 |
| `data/firstmob/damage_type/yao_spit.json` | 口水伤害类型（`scaling: never`） |
| `data/firstmob/damage_type/yao_melee.json` | 近战伤害类型（`scaling: never`，和平难度也生效） |
| `assets/firstmob/models/item/yao_yongshou_spawn_egg.json` | 平面刷怪蛋模型（无 parent、无厚度） |
| `assets/firstmob/textures/item/yao_yongshou_spawn_egg.png` | 刷怪蛋贴图 |

## 9. 不变量

改动代码后逐条确认：

1. `targetSelector` 始终为空，反击只由 `damage()` 驱动。
2. 同类永不被 `setTarget` 接受，口水也永不伤害同类。
3. `sonicMode == true` 时 `shootAt` 永不生成口水：普通形态的远程手段只由愤怒档位决定——警觉吐口水、激怒（100）放声波；锁定模式不进入 `sonicMode`，按距离分工。
4. 近战距离内不生成口水（普通 ≤3 格、锁定 ≤5 格）；普通模式 3~20 格吐口水，锁定模式 5~16 格吐口水、17~40 格放声波；锁定模式声波蓄力开始后必须先打完这一发。
5. 目标死亡当 tick 清空所有攻击状态；**残血锁定模式除外**——该模式下改锁最近的存活玩家。
6. 不存在幼年形态：`isBaby()` 恒 false、`getBreedingAge()` 恒 0、`createChild()` 返回 null。
7. 自定义音效事件必须用 `{"type": "event"}` 转发原版事件，不能直接引用其他命名空间的 `.ogg`。
8. 刷怪蛋模型不能写 `"parent": "item/generated"`。
9. 口水与近战的伤害类型都必须 `scaling: never`，否则和平难度无效。
10. 反击计数只在 `shootAt` / `tryAttack` 命中时扣减，别在 tick 里额外扣。
11. 锁定期间不执行 T2 / T3 / T4 / T8；锁定只由满血 + 30 秒无伤解除。
12. 空闲传送的偏角只随机在 35°~145°（左右随机），**绝不落在玩家正前方**；落点必须 3×3×4 空地；玩家周围找不到就退到 48~64 格外，5 秒后重试。
13. 粒子只在服务端 `ServerWorld#spawnParticles` 生成（客户端 `tick` 提前返回，不重复生成）；`dust_pillar` 必须带非空气、非流体的方块状态。
14. 护甲/韧性每 tick 按形态与血量重算，不靠一次性写入：普通 5/0、Boss 20/0、Boss 且 <20% 血 10/5。
15. Boss 形态只有在（非锁定模式）连续 600 tick 无目标时才退回普通形态；退回后血量仍 <80% 时，必须重新获得目标才再次变身（`bossFormDormant`），不能每 tick 反复进出 Boss 形态。
16. `initAttributes(Random)` 必须保持空实现：羊驼会在生成时随机最大生命 15~30，覆盖配置的 100，导致所有血量阈值失效。
17. 脱战回血只在玩家 10 秒（200 tick）未造成伤害且未满血时挂生命恢复 I；满血或被再次打中立即移除，且只移除本机制挂的无限恢复 I。
18. 听觉只有一个愤怒入口：振动是主来源，音效只补"同一动作没有 GameEvent"的声音（2 tick 配对）；不要再把音效与振动分别计数或相加。
19. 愤怒值每玩家独立：0~100、每 20 tick -1；`<30` 冷静、`30~99` 警觉、`100` 激怒；声波进入 100、退出 `<90`。
20. 好奇只在冷静且没有目标时触发：看声源 40 tick、40 tick 冷却，期间不让玩家注视；目标/声波/锁定出现立即停止。
21. 小噪音（脚步/落地/受击）也算真噪音，但每类 5 秒最多 1 次，且只加 5 点愤怒，避免走路自动激怒。
22. `Vibrations` 必须在服务端每 tick 推进 `Vibrations.Ticker.tick`，并在 `updateEventHandler` 中把 `EntityGameEventHandler` 交给回调；少于任一步都收不到 GameEvent。
23. Boss 形态完全不听声音，当前/最近玩家始终锁定（40 格内声波，之外追击）；锁定形态只做狂暴表现（血条颜色/标题），不改攻速与伤害。

## 10. 验收用例

| 编号 | 场景 | 操作 | 期望 |
| --- | --- | --- | --- |
| A1 | 走路不触发 | 20 格内只走路 10 秒 | 只好奇转头，不锁定、不吐口水 |
| A2 | 真噪音触发 | 20 格内 5 秒挖 3 个方块 | 进入警觉、锁定并开始吐口水 |
| A3 | 安静停火 | 触发后停止发声 8 秒 | 愤怒回落到 30 以下，清目标、不再追 |
| A4 | 超距放弃 | 警觉状态跑出 25 格 | 立刻放弃 |
| A5 | 声波升级 | 5 秒内挖 9 个方块 | 愤怒到 100，只出粒子束与声波音效，不再吐口水 |
| A6 | 声波距离 | 声波模式下跑到 41 格 | 退出声波 |
| A7 | 贴身禁远程 | 站在 3 格内 | 只挨近战，无口水/声波 |
| A8 | 拉开恢复 | 从 3 格退到 3.5 格 | 立刻恢复远程 |
| A9 | 反击上限 | 打它一下后不还手 | 最多还手 3 次即停 |
| A10 | 刷怪蛋 | 对地面与对实体分别使用 | 只生成成年体，不生成小羊驼 |
| A11 | 同类 | 两只姚永寿贴在一起 | 不互殴、口水不伤害同类 |
| A12 | 字幕 | 触发环境音/受伤/吐口水/声波 | 字幕均为「姚永寿：…」，无「羊驼/监守者」 |
| A13 | 和平难度 | 和平难度下被口水命中 | 仍受 4 点伤害 |
| A14 | 死亡脱战 | 目标被击杀 | 当 tick 停止攻击并清目标 |
| A15 | 生成即普通形态 | 刚召唤出来 | 血量 100、护甲 5、护甲韧性 0、体型 1 倍、没有 Boss 血条、没有速度/力量/抗性 |
| A16 | 80% 转 Boss | 打到 79 血以下 | 体型变 2 倍、护甲变 20、韧性 0、顶部出现「姚永寿」红色血条，并获得永久速度 I、力量 I、抗性提升 I |
| A17 | 变身保持 | 转 Boss 后持续有目标（或一直被攻击）30 秒 | 仍是 2 倍体型、20 护甲、血条与速度/力量/抗性都在 |
| A18 | 濒死触发 | 打到 19 血 | 获得伤害吸收 II、生命恢复 II、抗性提升、抗火；回血到 20% 以上再打回去会再触发一次 |
| A19 | 残血锁定 | 打到 59 血后跑动、保持安静、拉开 30 格 | 始终锁定玩家不放：5 格内近战、5~16 格口水、17~40 格声波 |
| A20 | 锁定追击加速 | 锁定后跑到 41 格外，再回到 19 格内 | 追击时一直挂速度 II，进入 5 格退回速度 I |
| A21 | 锁定近战 | 锁定后站到 4.5 格 | 只近战、不吐口水；退到 6 格恢复口水 |
| A22 | 锁定优先打它的玩家 | 两名玩家都在场，B 打了它一下 | 改锁 B，不再换回更近的 A |
| A23 | 锁定解锁 | 回满血且 30 秒没被玩家打中 | 解除锁定、清空目标；此后要重新靠声音/受击才会打人 |
| A24 | 低血加速 | 打到 19 血 | 口水间隔约 27 tick、声波循环约 128 tick、蓄力约 27 tick |
| A25 | 掉落经验 | 玩家击杀 | 掉落 500 点经验 |
| A26 | 常驻 | 召唤后跑出很远再回来 | 实体仍在原地，不会自然消失 |
| A27 | 铁傀儡式近战 | 贴身打一下 | 造成 15 点伤害（`firstmob:yao_melee`），目标被向上顶起，水平方向没有额外击退 |
| A28 | 声波出声距离 | 普通模式下站在 10 格听它放声波 | 20 格内不出声波，改吐口水；退到 21 格才蓄力开炮，超过 40 格停 |
| A29 | 空闲游走距离 | 召唤后直线跑出 40 格 | 20 格内它盯着你看；16~32 格站定不动；<16 格会后退；>48 格传送到你**侧前方约 20 格**（18~22 随机），不会出现在正前方视线里；游走时挂速度 III |
| A30 | 传送落点与退路 | 玩家站在悬崖/水边跑远 | 传送只落在 3×3×4 空地；玩家周围都没落点就退到 48~64 格外，之后每 5 秒重试 |
| A31 | 变身粒子 | 打到 79 血 | 身体中心出现橙→紫 `dust_color_transition` 尘团；回血后再打到 80% 以下不重复 |
| A32 | 濒死粒子 | 打到 19 血 | 身体中心出现 `totem_of_undying` 粒子；回血到 20% 以上再打到 19 血会再触发一次 |
| A33 | 速度拖尾 | 任意速度（I / II / III）走路 | 脚下持续冒 `firework`；站立不动时没有 |
| A34 | 近战扬尘 | 贴身命中一次 | 目标脚下升起 `dust_pillar` 尘柱，贴图为脚下方块 |
| A35 | 和平难度近战 | 和平难度下被近战命中 | 仍造成 15 点伤害（护甲/抗性照常减免），动作与 `dust_pillar` 照常播放 |
| A36 | 创造模式近战 | 创造模式下贴身被打 | 不掉血，但能看到抡臂动画与 `dust_pillar`，且不会残留伤害 |
| A37 | Boss 常驻抗性 | 转 Boss 形态 | 获得抗性提升 I（无限时长）；退回普通形态后该效果消失 |
| A38 | 濒死护甲 | Boss 形态打到 19 血；再回血到 21 血 | 19 血时护甲 10、韧性 5；21 血时立刻改回护甲 20、韧性 0 |
| A39 | 无目标退回 | Boss 形态 70 血、脱战 30 秒 | 体型回 1 倍、护甲 5、韧性 0、力量/抗性消失、血条收起；再被打一下立刻重新变身 |
| A40 | 锁定不退回 | <60% 锁定时保持 30 秒 | 仍是 Boss 形态；回满血并解除锁定后再 30 秒无目标，才退回普通形态 |
| A41 | 脱战回血 | 打到 50 血后停手 10 秒 | 获得生命恢复 I 并持续回血；再打一下立即移除恢复；回到满血自动停止 |
| A42 | 打断前先打完 | 锁定 25 格开始声波蓄力，随即贴到 10 格 | 仍把这发声波打完，期间不吐口水、不近战；结束后恢复 5~16 格口水 |
| A43 | 边追边声波 | 锁定时站在 35 格 | 一边靠近一边放声波；进入 32 格后站定放，进入 16 格改吐口水 |
| A44 | 羊毛挡声 | 隔一堵羊毛墙在另一侧 5 秒挖 3 个方块 | 不触发；拆掉羊毛后同样操作触发 |
| A45 | 潜行脚步 | 20 格内潜行来回走 10 秒 | 不锁定、不吐口水（振动忽略潜行脚步） |
| A46 | 潜行挖掘 | 潜行状态下 5 秒挖 3 个方块 | 仍触发（音效补充关闭，由振动听到） |
| A47 | 音效去重 | 正常状态下 5 秒挖 3 个方块 | 每次动作只加一次愤怒，日志不出现双倍 |
| A48 | 好奇回头 | 20 格内只挖 1 个方块 | 转头看声源约 40 tick，不锁定；40 tick 内再响不重复回头 |
| A49 | 好奇不盯人 | 好奇期间站到它侧面 | 视线跟随声源，不转去看玩家 |
| A50 | Boss 完全锁定 | 打到 79 血以下后保持安静、跑动 | 不再依赖声音，始终锁定当前/最近玩家；40 格内声波，之外追击 |
| A51 | Boss 不听声音 | Boss 形态下在 20 格内挖方块/吃喝 | 目标与攻击不变，不触发好奇或愤怒变化 |
| A52 | 锁定狂暴表现 | 打到 59 血以下进入锁定 | 血条变紫、标题带「（狂暴）」；攻速/伤害与锁定前一致 |

## 11. 已知限制

- 刷怪蛋是单面平面模型：侧视是一条线，掉落物转到背面看不到图案。
- 创造模式玩家免疫伤害：口水只击退；近战与声波不结算伤害，其中近战的动作与尘柱照常播放，声波只剩粒子与音效。
- 声波沿用 `minecraft:sonic_boom`（`scaling: always`），和平难度归零。
- 只保留僵尸外观，交易、职业、脑 AI 一概不继承。
- 听觉只统计玩家，怪物或环境音效不会吸引姚永寿。
- 振动按距离延迟结算，最远 20 格时最多晚 1 秒；待处理振动不写 NBT，重登会清空。
- 音效补充靠 2 tick 配对：音效与对应 GameEvent 相隔超过 2 tick 时可能重复计一次或漏计。
- 潜行时音效补充关闭；没有对应 GameEvent 的自定义音效在潜行时听不到。
- 狂暴只改血条表现，不改攻速与伤害。
