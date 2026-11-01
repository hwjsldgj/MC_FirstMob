# 行为规范（SPEC）

> 本文是姚永寿行为的**唯一事实来源**：代码常量、README 的描述与本文不一致时，先确认哪边是当前设计，再让两边一致。
> 注册 ID、Java 接口与资源契约见 [API.md](API.md)；面向玩家的简介见 [README](../README.md)；工程约定见 [AGENTS](../AGENTS.md)。

## 1. 术语

| 术语 | 含义 |
| --- | --- |
| 真噪音（OTHER） | 除走路、跳跃、受击之外的玩家声音，以及挖掘/交互方块的**动作**；唯一能触发并维持仇恨的声音类别 |
| 锁定 | `setTarget(player)`，目标是一名玩家 |
| 保证攻击 | 声音触发后 5 秒内至少要打出一次攻击（口水或近战） |
| 普通模式 | 未进入声波模式的状态（空闲 / 声音追击 / 挨打反击） |
| 声波模式 | `sonicMode == true`，只放声波、不吐口水 |
| 窗口 | 最近 100 tick（5 秒）的时间窗，含边界：`now - t <= 100` 仍计入 |

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
| Boss 血条 | 仅 Boss 形态显示：屏幕顶部红色进度条，标题「姚永寿」，每 10 tick 同步一次血量，死亡当 tick 收起 |
| 残血锁定 | 血量 < 60% 起死咬玩家：≤20 格吐口水、>20 格声波，满血且 30 秒无玩家伤害才解除；见 [§3.4](#34-残血锁定60) |
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
| `NOISE` | `noiseTarget != null && !sonicMode && !revengeMode` | 被真噪音吸引，安静即停 |
| `REVENGE` | `revengeMode && !sonicMode` | 挨打后的有限反击 |
| `SONIC` | `sonicMode` | 监守者声波，只有超出 40 格、目标死亡或被打断才停 |

### 3.2 转移

| # | 从 | 触发 | 处理 | 到 |
| --- | --- | --- | --- | --- |
| T1 | 任意（非 SONIC） | 同一玩家窗口内 OTHER ≥ 3 | 记 `noiseTarget`、`guaranteedShots = 1`、清反击计数、`setTarget(player)` | `NOISE` |
| T2 | 任意（非 SONIC） | 同一玩家窗口内 OTHER ≥ 9 | `enterSonicMode`：记 `noiseTarget`、清保证与反击、`setTarget(player)` | `SONIC` |
| T3 | `NOISE` | 窗口内 OTHER < 3 且无保证攻击 | 清目标、清 `noiseTarget` | `IDLE` |
| T4 | `NOISE` | 与玩家目标距离 > 25 格 | 清目标、清 `noiseTarget`、清保证 | `IDLE` |
| T5 | 任意 | 当前目标死亡 | 当 tick 清目标及所有攻击状态 | `IDLE` |
| T6 | 任意（非 SONIC） | 被非同类生物/玩家打中 | 清 `noiseTarget` 与保证，置 `revengeShots = 3`、`revengeDeadline = now + 100`、`setTarget(attacker)` | `REVENGE` |
| T7 | `REVENGE` | 打满 3 次 | `setAttacker(null)`、`setTarget(null)` | `IDLE` |
| T8 | `REVENGE` | 5 秒内一次都没打出去 | 同 T7 | `IDLE` |
| T9 | `SONIC` | 距离 > 40 格、目标死亡或下线 | `exitSonicMode`：清该玩家声音记录、清目标 | `IDLE` |
| T10 | `SONIC` | 被**另一个玩家**打中 | `noiseTarget` 换成他、`sonicTicks = 0`、`setTarget`，不退出声波 | `SONIC` |
| T11 | `SONIC` | 被非玩家生物打中 | `exitSonicMode` 后按 T6 进入反击 | `REVENGE` |

补充：

- T1 与 T2 都在每次声音记账后判定；T2 优先于 T1。
- `SONIC` 期间不再接受声音触发（进入判定带 `!sonicMode`），但仍在累计声音，退出后清空。
- 25 格放弃（T4）只对**玩家目标**生效；非玩家目标靠 T8 的 5 秒超时收手。
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
- 濒死效果合并 1.8 老版与 1.9+ 现版两套：伤害吸收 III（2 分钟）、生命恢复 II（30 秒，取老版更长的时长）、抗性提升（5 分钟）、抗火（5 分钟）。
- `bossForm` / `bossFormDormant` / `lowHealthTriggered` 写入 NBT：重载后不会丢掉 Boss 形态，也不会把"已退回普通形态"读成未变身。
- 濒死触发后若回血越过 20%、再掉回 20% 以下，会再次触发（`lowHealthTriggered` 随回血复位）。

### 3.4 残血锁定（<60%）

血量低于 60% 时进入锁定模式：不再按声音/距离规则脱战，而是死咬玩家。

| 项 | 规则 |
| --- | --- |
| 进入 | `getHealth() < 60% × maxHealth`（每 tick 判定；进入时清空声音/反击/声波状态） |
| 目标 | 优先最近一次打它的玩家；没有则最近的存活玩家（不限距离） |
| 攻击 | 距离 < 5 格：只近战（比普通模式的 3 格更早贴脸）；5~20 格：吐口水；20~40 格：蓄力后放声波；> 40 格只追不打 |
| 移速 | 追玩家时全程挂速度 II（锁定模式与普通追击都一样）：进入近战距离就收回，退回 Boss 形态的永久速度 I |
| 解锁 | 自己满血 **且** 30 秒（600 tick）内没有玩家对它造成伤害，两条同时满足才解除 |
| 目标死亡 | 该玩家锁定结束，当 tick 改锁最近的存活玩家；没有玩家就停手等待（仍保持锁定状态） |
| 声音 | 锁定期间 `hearSoundFrom` 直接忽略，不会改动目标 |
| 其他生物 | 被打时不换目标；不进入反击计数，也不受 3 次还手上限约束 |

- 锁定期间不执行"安静停火"（T3）、"25 格放弃"（T4）、"反击 5 秒超时"（T8）与噪音声波（T2/T9）。
- 声波节拍与噪音声波一致：蓄力 34 tick、循环 160 tick；`<20%` 血时按 80% 计算（27 tick / 128 tick）。
- 吐口水冷却同样参与 `<20%` 的 20% 缩短（34 → 27 tick），两种模式共用一套冷却记录。
- 速度 II 只在锁定模式生效；20~40 格不切换是为了避免玩家在阈值附近来回走时反复加减速。
- 护甲 20 + 抗性提升 I 让 100 血非常耐打；锁定是"状态"而非"一次事件"，回血到 60% 以上也不会自动解除。

### 3.5 空闲跟随与传送

没有目标、也没有进入锁定时（`IDLE`），姚永寿围着最近的存活玩家游走，**始终保持 ≥16 格站距**：

| 距离 | 行为 |
| --- | --- |
| ≤ 20 格 | 每 tick `lookAt` 玩家（头部一直盯着看；`LookAtEntityGoal` 只有 8 格，覆盖不到这么远） |
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

## 4. 听觉

### 4.1 声音来源

| 来源 | 注入点 | 计入 |
| --- | --- | --- |
| 玩家音效 | `PlayerEntity#playSound(SoundEvent, float, float)` 的 HEAD（`PlayerSoundMixin`） | 按 4.2 分类 |
| 挖掘方块 | `ServerPlayerInteractionManager#tryBreakBlock` 的 HEAD（`PlayerInteractionMixin`） | 一次 OTHER |
| 方块交互（放方块、开箱子等） | `ServerPlayerInteractionManager#interactBlock` 的 HEAD（同上） | 一次 OTHER |

丢弃规则（服务端统一过滤）：客户端世界、玩家死亡、旁观者、音效 ID 以 `.death` 结尾。

分发：以玩家碰撞箱 `expand(20)` 搜索 `YaoYongshouEntity`，再用 `squaredDistanceTo(player) <= 20²` 精确过滤；每个姚永寿独立记账。

### 4.2 分类

按 `SoundEvent` ID 的 **path 后缀**判断（不含命名空间）：

| 类别 | 后缀 | 计数上限 |
| --- | --- | --- |
| `WALK` | `.step` | 每玩家每 5 秒 1 次 |
| `JUMP` | `.small_fall`、`.big_fall` | 每玩家每 5 秒 1 次 |
| `HURT` | `.hurt` | 每玩家每 5 秒 1 次 |
| `OTHER` | 其余全部 | 不限，发生即记 1 次 |

"每 5 秒 1 次"的实现：若该玩家上次同类记录满足 `now - last < 100`，本次直接丢弃。

### 4.3 记账

- 所有声音都会追加到 `recentSounds`；**只有 OTHER** 追加到 `recentLoudSounds`。
- 判定只读 `recentLoudSounds`；`recentSounds` 目前只记录（退出声波时按 UUID 清除），不参与决策。
- 过期条目：`now - 最早时间戳 > 100` 时移除；每 100 tick 额外全量清理一次。

### 4.4 判定

| 判定 | 条件 | 结果 |
| --- | --- | --- |
| 触发口水 | `loud ≥ 3` | T1 |
| 升级声波 | `loud ≥ 9` | T2，且立即返回，不再走口水逻辑 |
| 维持 | 已锁定该玩家且 `loud ≥ 3`，或保证期未过 | 保持目标 |
| 停火 | `loud < 3` 且无保证攻击 | T3 |
| 放弃 | 玩家目标 > 25 格 | T4 |

保证攻击（`guaranteedShots`）：

- 触发时置 1；首发口水、或近战命中且目标 UUID 匹配时减 1。
- 超过 `GUARANTEE_TIMEOUT_TICKS`（100 tick）仍未兑现则作废，避免被墙挡住时永久锁敌。

## 5. 攻击

### 5.1 Goal 优先级

`initGoals()` 先 `super.initGoals()`，随后 `goalSelector` 与 `targetSelector` **全部清空**，再按表重建：

| 优先级 | Goal | 参数 | 职责 |
| --- | --- | --- | --- |
| 0 | `SwimGoal` | — | 浮水 |
| 2 | `CloseMeleeGoal`（`MeleeAttackGoal` 子类） | 速度 1.2，`pauseWhenMobIdle = true` | 3 格内近战 |
| 3 | `ProjectileAttackGoal` | 速度 1.25，间隔 34 tick，射程 20 | 走近并吐口水 |
| 6 | `IdleWanderGoal`（`WanderAroundFarGoal` 子类） | 速度 1.0 | 游荡；玩家在 16~20 格的"合适距离"内时 `canStart` / `shouldContinue` 都返回 false，站定只看人 |
| 7 | `LookAtEntityGoal` | 玩家，8 格 | 看人 |
| 8 | `LookAroundGoal` | — | 随机张望 |

`targetSelector` 保持为空：原版 `SpitRevengeGoal` 会在目标被清空后重新锁定上次攻击者，反击因此由 `damage()` 自行接管。

### 5.2 远程口水

- 进入条件：目标存在、非声波模式、距离 > 3 格。
- 首发：`setTarget` 成功锁定新目标的瞬间，若距离 ≤ 20 格且 `canSee`，立即 `shootAt` 一次（仍受 3/5 格限制），并记一次冷却；被激怒时同样适用。
- 守卫：客户端、`sonicMode`、距离 ≤ 3 格（锁定模式 5 格），任一成立直接返回，不生成弹体；锁定模式的冷却另由 `nextSpitTick` 控制。
- 弹体：复用 `EntityType.LLAMA_SPIT`，实体类 `YaoYongshouSpitEntity`。
- 弹道：出膛点 `(x, eyeY - 0.1, z)`；速度 2.5，散布 0.4；竖直方向补偿 `drop = 0.5 × 0.06 × t²`，其中 `t = 水平距离 / 2.5`。
- 音效：`firstmob:entity.yao_yongshou.spit`（`SoundCategory.NEUTRAL`，音量/音调 1.0）。
- 每次发射都会调用 `countRevengeShot()`，并尝试兑现保证攻击。

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
- `tryAttack` 命中后同样兑换保证攻击、扣反击计数。
- 声波模式期间近战**照常**可用（只有远程被禁）。

### 5.4 挨打反击

- 触发：`damage()` 结算成功后，攻击者为 `LivingEntity`、非自身、非同类。
- 状态：`revengeMode = true`、`revengeShots = 3`、`revengeDeadline = now + 100`、`setTarget(attacker)`。
- 扣减：每次 `shootAt` 发射、每次 `tryAttack` 命中各扣 1，并刷新 deadline。
- 结束：扣到 0（T7）或超时（T8）时清空 attacker 与 target。
- 声音触发与进入声波都会清掉 `revengeMode`，避免反击计数打断新的战斗。
- 反击期间不受"安静停火"影响，但仍受 25 格放弃（玩家目标）约束。
- 例外：声波模式下被**锁定目标本人**打中不会触发反击（保持声波锁定）；被另一个玩家打中则切换 `noiseTarget`，声波不中断。

### 5.5 声波（监守者）

触发：同一玩家、20 格内、窗口内 OTHER ≥ 9；判定在口水触发之前，且 `sonicMode` 为 true 时不再重复进入。

节拍（`sonicTicks` 从 0 开始，每 tick +1）：

| tick | 行为 |
| --- | --- |
| 每 tick | `faceTarget`，随后 `sonicTicks++` |
| 0 | 播放蓄力音 `firstmob:entity.yao_yongshou.sonic_charge` |
| 34 | 若与目标距离 > 3 格，`fireSonicBoom` |
| 160 | `sonicTicks` 归零，进入下一轮（再次蓄力） |

- 声波模式是"9 次噪音"换来的，触发后**替代口水**：除贴身近战外不再吐口水，也不再按 20 格切回口水。
- 没到 9 次噪音时，无论距离多远都只吐口水（超过 20 格会先走近），不会自己改放声波。
- 20 格以外的声波最远到 40 格：目标跑出 40 格退出声波模式。
- 每 tick 按 UUID 重新解析目标并 `setTarget`，中途被其他生物抢仇恨不影响声波锁定。
- 停止：目标为空/死亡/下线、距离 > 40 格（T9），或被非玩家生物打中（T11）。
- 退出时清除该玩家的 `recentSounds` 与 `recentLoudSounds`，必须重新攒满 9 次才能再次触发。

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
| `MAX_CHASE_RANGE` | 25 | 格 | 普通模式放弃距离（仅玩家目标） |
| `SOUND_WINDOW_TICKS` | 100 | tick | 计数窗口（5 秒） |
| `SOUND_THRESHOLD` | 3 | 次 | 口水触发阈值 |
| `RELEASE_SOUND_COUNT` | 3 | 次 | 停火阈值 |
| `GUARANTEE_TIMEOUT_TICKS` | 100 | tick | 保证攻击有效期 |
| `SPIT_COOLDOWN_TICKS` | 34 | tick | 吐口水间隔（原版 40） |
| `SPIT_RANGE` | 20 | 格 | 吐口水射程 |
| `MELEE_RANGE_SQUARED` | 9 | 格² | 3 格近战接管 / 远程禁用距离 |
| `ATTACK_DAMAGE` | 15 | 点 | 近战伤害（铁傀儡数值） |
| `ATTACK_KNOCKBACK` | 0 | — | 近战击退属性（铁傀儡没有加成） |
| `GOLEM_KNOCKUP` | 0.4 | 倍 | 命中后向上顶的速度（× (1 - 击退抗性)） |
| `REVENGE_SHOT_LIMIT` | 3 | 次 | 每次挨打最多还手次数 |
| `REVENGE_TIMEOUT_TICKS` | 100 | tick | 反击超时 |
| `SONIC_SOUND_THRESHOLD` | 9 | 次 | 声波触发阈值 |
| `SONIC_RANGE` | 40 | 格 | 声波解除距离 |
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
3. `sonicMode == true` 时 `shootAt` 永不生成口水：普通模式的远程手段只由"5 秒内噪音是否 ≥ 9"决定，与距离无关；锁定模式不进入 `sonicMode`，按距离分工。
4. 距离 ≤ 3 格时不生成口水（锁定模式 5 格）；声波只在 20~40 格开炮，20 格内不蓄力、不结算。
5. 目标死亡当 tick 清空所有攻击状态；**残血锁定模式除外**——该模式下改锁最近的存活玩家。
6. 不存在幼年形态：`isBaby()` 恒 false、`getBreedingAge()` 恒 0、`createChild()` 返回 null。
7. 自定义音效事件必须用 `{"type": "event"}` 转发原版事件，不能直接引用其他命名空间的 `.ogg`。
8. 刷怪蛋模型不能写 `"parent": "item/generated"`。
9. 口水与近战的伤害类型都必须 `scaling: never`，否则和平难度无效。
10. 反击计数只在 `shootAt` / `tryAttack` 命中时扣减，别在 tick 里额外扣。
11. 锁定期间不执行 T3 / T4 / T8，也不进入噪音声波；锁定只由满血 + 30 秒无伤解除。
12. 空闲传送的偏角只随机在 35°~145°（左右随机），**绝不落在玩家正前方**；落点必须 3×3×4 空地；玩家周围找不到就退到 48~64 格外，5 秒后重试。
13. 粒子只在服务端 `ServerWorld#spawnParticles` 生成（客户端 `tick` 提前返回，不重复生成）；`dust_pillar` 必须带非空气、非流体的方块状态。
14. 护甲/韧性每 tick 按形态与血量重算，不靠一次性写入：普通 5/0、Boss 20/0、Boss 且 <20% 血 10/5。
15. Boss 形态只有在（非锁定模式）连续 600 tick 无目标时才退回普通形态；退回后血量仍 <80% 时，必须重新获得目标才再次变身（`bossFormDormant`），不能每 tick 反复进出 Boss 形态。
16. `initAttributes(Random)` 必须保持空实现：羊驼会在生成时随机最大生命 15~30，覆盖配置的 100，导致所有血量阈值失效。
17. 脱战回血只在玩家 10 秒（200 tick）未造成伤害且未满血时挂生命恢复 I；满血或被再次打中立即移除，且只移除本机制挂的无限恢复 I。

## 10. 验收用例

| 编号 | 场景 | 操作 | 期望 |
| --- | --- | --- | --- |
| A1 | 走路不触发 | 20 格内只走路 10 秒 | 不锁定、不吐口水 |
| A2 | 真噪音触发 | 20 格内 5 秒挖 3 个方块 | 锁定并开始攻击 |
| A3 | 安静停火 | 触发后停止发声 6 秒 | 清目标，不再追 |
| A4 | 超距放弃 | 触发后跑出 25 格 | 立刻放弃 |
| A5 | 声波升级 | 5 秒内挖 9 个方块 | 只出粒子束与声波音效，不再吐口水 |
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
| A18 | 濒死触发 | 打到 19 血 | 获得伤害吸收 III、生命恢复 II、抗性提升、抗火；回血到 20% 以上再打回去会再触发一次 |
| A19 | 残血锁定 | 打到 59 血后跑动、保持安静、拉开 30 格 | 始终锁定玩家不放，≤20 格吐口水、>20 格放声波 |
| A20 | 锁定追击加速 | 锁定后跑到 41 格外，再回到 19 格内 | 出 40 格挂速度 II，进 20 格退回速度 I（20~40 格之间不变） |
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

## 11. 已知限制

- 刷怪蛋是单面平面模型：侧视是一条线，掉落物转到背面看不到图案。
- 创造模式玩家免疫伤害：口水只击退；近战与声波不结算伤害，其中近战的动作与尘柱照常播放，声波只剩粒子与音效。
- 声波沿用 `minecraft:sonic_boom`（`scaling: always`），和平难度伤害归零。
- 方块声音按「挖/交互动作」计数，不按真实音效事件；交互失败、开箱子等也会计一次。
- 只保留僵尸外观，交易、职业、脑 AI 一概不继承。
- 听觉只统计玩家，怪物或环境音效不会吸引姚永寿。
