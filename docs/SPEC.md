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
| 外形 | 村民 `VillagerResemblingModel` + `minecraft:textures/entity/villager/villager.png`，不继承村民实体 |
| 碰撞箱 | 宽 0.6 × 高 1.95，眼高 1.62 |
| 生成组 | `SpawnGroup.MISC` |
| 追踪范围 | 10（`EntityType.Builder#maxTrackingRange`） |
| 属性 | 原版羊驼属性 + `GENERIC_ATTACK_DAMAGE = 4` + `GENERIC_ATTACK_KNOCKBACK = 0.5` |
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
| 6 | `WanderAroundFarGoal` | 速度 1.0 | 游荡 |
| 7 | `LookAtEntityGoal` | 玩家，8 格 | 看人 |
| 8 | `LookAroundGoal` | — | 随机张望 |

`targetSelector` 保持为空：原版 `SpitRevengeGoal` 会在目标被清空后重新锁定上次攻击者，反击因此由 `damage()` 自行接管。

### 5.2 远程口水

- 进入条件：目标存在、非声波模式、距离 > 3 格。
- 首发：`setTarget` 成功锁定新目标的瞬间，若 `!sonicMode`、距离 ≤ 20 格且 `canSee`，立即 `shootAt` 一次（仍受 3 格限制）；被激怒时同样适用。
- 守卫：客户端、`sonicMode`、距离 ≤ 3 格，任一成立直接返回，不生成弹体。
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
- 近战伤害 4、攻击击退 0.5（属性加成）；`tryAttack` 命中后同样兑换保证攻击、扣反击计数。
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

- 贴身时（≤ 3 格）第 34 tick 不开炮，也不顺延；要等下一轮 160 tick 后的第 34 tick。
- 每 tick 按 UUID 重新解析目标并 `setTarget`，中途被其他生物抢仇恨不影响声波锁定。
- 停止：目标为空/死亡/下线、距离 > 40 格（T9），或被非玩家生物打中（T11）。
- 退出时清除该玩家的 `recentSounds` 与 `recentLoudSounds`，必须重新攒满 9 次才能再次触发。
- 与口水互斥：`shootAt` 在 `sonicMode` 时直接返回；但近战不受影响。

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
| `REVENGE_SHOT_LIMIT` | 3 | 次 | 每次挨打最多还手次数 |
| `REVENGE_TIMEOUT_TICKS` | 100 | tick | 反击超时 |
| `SONIC_SOUND_THRESHOLD` | 9 | 次 | 声波触发阈值 |
| `SONIC_RANGE` | 40 | 格 | 声波解除距离 |
| `SONIC_CHARGE_TICKS` | 34 | tick | 蓄力时长 |
| `SONIC_INTERVAL_TICKS` | 160 | tick | 声波循环长度（8 秒） |
| `SONIC_DAMAGE` | 10 | 点 | 声波伤害 |

## 7. 音效与文本

| 事件 ID | 复用的原版事件 | 字幕键 | 中文文案 |
| --- | --- | --- | --- |
| `firstmob:entity.yao_yongshou.ambient` | `minecraft:entity.llama.ambient` | `subtitles.firstmob.entity.yao_yongshou.ambient` | 姚永寿：吼叫 |
| `firstmob:entity.yao_yongshou.hurt` | `minecraft:entity.llama.hurt` | `...hurt` | 姚永寿：受伤 |
| `firstmob:entity.yao_yongshou.death` | `minecraft:entity.llama.death` | `...death` | 姚永寿：死亡 |
| `firstmob:entity.yao_yongshou.spit` | `minecraft:entity.llama.spit` | `...spit` | 姚永寿：吐口水 |
| `firstmob:entity.yao_yongshou.sonic_charge` | `minecraft:entity.warden.sonic_charge` | `...sonic_charge` | 姚永寿：蓄力 |
| `firstmob:entity.yao_yongshou.sonic_boom` | `minecraft:entity.warden.sonic_boom` | `...sonic_boom` | 姚永寿：音波尖啸 |

死亡信息：`death.attack.yao_spit` → `%1$s 被 %2$s 的口水淹死了`（英文：`%1$s was drowned in %2$s's spit`）。

## 8. 资源契约

文件级契约（字段含义、校验点）见 [API.md 的「资源与数据契约」](API.md#5-资源与数据契约)。摘要：

| 文件 | 作用 |
| --- | --- |
| `assets/firstmob/sounds.json` | 6 个自定义事件，全部以 `{"type": "event"}` 转发原版事件 |
| `assets/firstmob/lang/zh_cn.json` / `en_us.json` | 实体名、物品名、字幕、死亡信息 |
| `data/firstmob/damage_type/yao_spit.json` | 口水伤害类型（`scaling: never`） |
| `assets/firstmob/models/item/yao_yongshou_spawn_egg.json` | 平面刷怪蛋模型（无 parent、无厚度） |
| `assets/firstmob/textures/item/yao_yongshou_spawn_egg.png` | 刷怪蛋贴图 |

## 9. 不变量

改动代码后逐条确认：

1. `targetSelector` 始终为空，反击只由 `damage()` 驱动。
2. 同类永不被 `setTarget` 接受，口水也永不伤害同类。
3. `sonicMode == true` 时 `shootAt` 永不生成口水。
4. 距离 ≤ 3 格时不生成口水，声波也不结算（蓄力照常推进）。
5. 目标死亡当 tick 清空所有攻击状态。
6. 不存在幼年形态：`isBaby()` 恒 false、`getBreedingAge()` 恒 0、`createChild()` 返回 null。
7. 自定义音效事件必须用 `{"type": "event"}` 转发原版事件，不能直接引用其他命名空间的 `.ogg`。
8. 刷怪蛋模型不能写 `"parent": "item/generated"`。
9. 口水伤害类型必须 `scaling: never`，否则和平难度无效。
10. 反击计数只在 `shootAt` / `tryAttack` 命中时扣减，别在 tick 里额外扣。

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

## 11. 已知限制

- 刷怪蛋是单面平面模型：侧视是一条线，掉落物转到背面看不到图案。
- 创造模式玩家免疫伤害：口水只击退；声波连击退也不结算，只剩粒子与音效。
- 声波沿用 `minecraft:sonic_boom`（`scaling: always`），和平难度伤害归零。
- 方块声音按「挖/交互动作」计数，不按真实音效事件；交互失败、开箱子等也会计一次。
- 只保留村民外观，交易、职业、脑 AI 一概不继承。
- 听觉只统计玩家，怪物或环境音效不会吸引姚永寿。
