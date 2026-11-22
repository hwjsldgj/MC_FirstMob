# 行为规范（SPEC）

> 本文是姚永寿行为的**唯一事实来源**：代码常量、README 的描述与本文不一致时，先确认哪边是当前设计，再让两边一致。
> 注册 ID、Java 接口与资源契约见 [API.md](API.md)；面向玩家的简介见 [README](../README.md)；工程约定见 [AGENTS](../AGENTS.md)。

## 1. 术语

| 术语 | 含义 |
| --- | --- |
| 阶段 | 1~4，按当前血量比例取档；Boss 形态下限为 2 |
| 真噪音 | 玩家发出的、被听觉接收的声音；大噪音 +12，脚步/落地/受击 +5（每类 5 秒最多 1 次） |
| 愤怒 | 仅阶段 1 使用；每玩家 0~100，每 20 tick -1；30 警觉、100 激怒 |
| 好奇 | 阶段 1 冷静时听到声音后转向声源 40 tick |
| 硬锁 | 阶段 2+ 不听声音，直接锁定当前/最近玩家 |
| 穿甲 | 指定点数的伤害不参与护甲减伤，抗性/保护照常结算 |

1 tick = 1/20 秒。

## 2. 实体基线

| 项 | 值 |
| --- | --- |
| 实体 ID | `hush:yao_yongshou` |
| Java 基类 | `LlamaEntity`（只沿用普通寻路 + 吐口水） |
| 外形 | 僵尸 `BipedEntityModel`（`EntityModelLayers.ZOMBIE`）+ 原版僵尸贴图；待机双臂前伸 |
| 碰撞箱 | 宽 0.6 × 高 1.95，眼高 1.62；`GENERIC_SCALE = 2` 后实际 1.2 × 3.9 |
| 生成组 / 追踪范围 | `SpawnGroup.MISC` / 10 |
| 属性 | 羊驼属性 + `max_health = 100` + `scale = 1` + `armor = 5` + `armor_toughness = 0` + `attack_damage = 5` + `attack_knockback = 0` |
| 血量 | 100 点；生成时满血 |
| 形态 | 普通（阶段 1）⇄ Boss（阶段 2~4）；Boss 形态 `scale = 2`、显示 Boss 血条 |
| 常驻 | `setPersistent()`：只能被打死或指令移除 |
| 掉落经验 | 500 点 |
| 幼年 / 繁殖 / 喂食 / 驯服 / 骑乘 | 全部禁用；同类不索敌、口水不伤同类 |
| 扫射 | `targetSelector` 始终为空，反击由 `damage()` 自行计数 |

## 3. 阶段与形态

### 3.1 阶段表

阶段按**当前血量比例**动态取档；回血跨过阈值会退回低阶段并撤掉高阶增益，但退出 Boss 形态只走 §3.3 的退回机制。

| 阶段 | 血量比例 | 护甲 | 近战 | 口水 | 声波 | 追击 / 脱锁 | 常驻增益 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | ≥80% | 5 | ≤5 格：5 | 6~24 格：3，无击退 | 17~24 格：6，全穿甲（仅愤怒） | 由远程 Goal 接近；>24 脱锁 | — |
| 2 | 60%≤r<80% | 10 | ≤5 格：8 | 6~16 格：4，击退 I | 17~32 格：8 | 停在 16 格；>32 脱锁 | 抗性提升 I、速度 I |
| 3 | 30%<r<60% | 15 | ≤5 格：8（4 穿甲） | 6~16 格：5（3 穿甲），击退 II | 17~48 格：10 | 停在 16 格；永不脱锁 | 抗性提升 I、速度 I、力量 I |
| 4 | ≤30% | 20 | ≤5 格：10（5 穿甲） | 6~16 格：6（4 穿甲），击退 II；17~24 格随机档 | 25~64 格：14；17~24 格随机档 | 停在 32 格；>64 仅追且速度再 +1；永不脱锁 | 抗性提升 I、速度 I、力量 I + 额外增益 |

- 阶段 2/3 的声波区间包含“边追边打”段（25~32 / 33~48）；阶段 4 的 49~64 格也是边追边打声波，>64 格只追不打。
- 阶段 4 的 17~24 格随机档：每次攻击判定 75% 口水 8 点（无穿甲、击退 II），25% 声波 12 点（立即开炮，不蓄力）。
- 所有阶段的近战接管距离统一为 5 格；护甲韧性恒为 0。
- 阶段 1 的接近/停距由原版 `ProjectileAttackGoal` 决定（代码没有单独限制 8 格）；`CombatProfile.chaseStopRange` 只在阶段 2~4 的 Boss 走位里生效。

### 3.2 形态进入与增益

- 血量 <80% 且（未退回或重新获得目标）→ 进入 Boss 形态：体型 2 倍、阶段下限 2、释放一次 `dust_color_transition` 粒子。
- Boss 形态常驻抗性提升 I（无限时长）；阶段 3+ 再挂力量 I。
- 回血跨过阈值：阶段按当前血量降档，力量 I 在回到 60% 以上时移除；抗性/速度随 Boss 形态保留。
- 阶段 4 触发：血量从 >30% 跌破 ≤30%，冷却 60 秒（1200 tick）已好 → 获得抗性 I、生命恢复 II、速度 I、力量 I、伤害吸收 I，各 3 分钟（3600 tick），并释放一次 `totem_of_undying` 粒子；可重复触发。

### 3.3 退回普通形态

- Boss 形态连续 600 tick（30 秒）没有目标 → 退回普通形态：体型 1 倍、护甲 5、力量/抗性移除、血条收起、阶段回到 1。
- 退回后血量仍 <80% 时保持普通形态；**重新获得目标**（被打、被激怒、进入硬锁）会立刻再次变身。
- 回血到 ≥80% 清除 `bossFormDormant`，下次掉到 80% 以下仍照常变身。

### 3.4 索敌

- 阶段 1：保留听觉/愤怒、好奇、受击反击；目标跑出 24 格脱锁。
- 阶段 2：完全不听声音，硬锁当前/最近玩家；超过 32 格脱锁（回到 32 格内会再次锁定）。
- 阶段 3/4：完全不听声音，优先上次攻击者，其次当前/最近玩家；**永不因距离脱锁**。
- 被玩家打中时刷新脱战回血计时；阶段 3/4 还会把攻击者记为硬锁目标。

## 4. 听觉与愤怒（仅阶段 1）

### 4.1 声音来源

| 来源 | 注入点 | 说明 |
| --- | --- | --- |
| 原版振动（主） | 实体实现 `Vibrations` | 只收 `WARDEN_CAN_LISTEN`、发射者为玩家；20 格 |
| 玩家音效（补充） | `PlayerEntity#playSound` 的 HEAD（`PlayerSoundMixin`） | 只补"同一动作没有 GameEvent"的声音；2 tick 配对 |

- 振动按距离延迟约 1 格 = 1 tick；`accepts` 同 tick 记标记，音效靠标记去重。
- 丢弃：客户端世界、死亡、旁观者、`.death` 音效；怪物/爆炸/发射器不算。
- 羊毛按 `BlockTags.OCCLUDES_VIBRATION_SIGNALS` 遮挡；潜行脚步声被振动忽略、音效补充关闭。

### 4.2 分类与愤怒

| 来源 | 类别 | 愤怒 |
| --- | --- | --- |
| `STEP` / `HIT_GROUND` / `ENTITY_DAMAGE` | 小噪音 | +5，每类 5 秒限一次 |
| 其余 `GameEvent` / 未记录音效 | 大噪音 | +12 |

- 每玩家独立 0~100；每 20 tick 全体 -1；`<30` 冷静、`30~99` 警觉、`100` 激怒；声波进入 100、退出 `<90`。
- prime suspect：愤怒最高的存活玩家，同分取最近。

### 4.3 好奇

- 冷静且无目标时听到声音：转向声源 40 tick、40 tick 冷却；目标/声波/锁定出现立即停止。
- 好奇只转头，不改变寻路，期间不让玩家注视打断。

### 4.4 阶段 1 档位行为

| 状态 | 行为 |
| --- | --- |
| 冷静 | 好奇；`<30` 清空目标 |
| 警觉 | 锁 prime suspect；24 格内追击、6~24 格吐口水 |
| 激怒 | 17~24 格改放声波，6~16 格仍吐口水，>24 格脱锁 |
| 受击反击 | 每次被打最多还手 3 次（口水/近战都算），5 秒超时 |

### 4.5 注视静止（非 Boss）

- 判定：32 格内任一存活、非旁观玩家，其视线方向与「玩家眼睛 → 姚永寿眼睛」的夹角 ≤ 60°（`GazeWatch.MAX_ANGLE_DEGREES`）。
- 适用状态：仅非 Boss 形态且**无目标**（空闲／游走）；一旦锁定目标进入战斗就不生效。
- 效果：每个 tick 取消寻路，原地不动；攻击与注视机制不变。
- Boss 形态（<80%）不受影响。
- 已知限制：玩家持续注视即可让它原地不动；不做视线遮挡判定，隔墙注视同样生效。

## 5. 攻击

### 5.1 Goal 优先级

`initGoals()` 先 `super.initGoals()`，随后 `goalSelector` 与 `targetSelector` 全部清空，再按表重建：

| 优先级 | Goal | 参数 | 职责 |
| --- | --- | --- | --- |
| 0 | `SwimGoal` | — | 浮水 |
| 1 | `CuriousLookGoal` | — | 阶段 1 好奇看声源 |
| 2 | `CloseMeleeGoal` | 速度 1.2，`pauseWhenMobIdle = true` | 5 格内近战；声波蓄力中让路 |
| 3 | `ProjectileAttackGoal` | 速度 1.25，间隔 34 tick，射程 24 | 阶段 1 走近并吐口水；Boss 阶段只留视线 |
| 6 | `IdleWanderGoal` | 速度 1.0 | 游荡；16~32 格站定看人 |
| 7 | `CalmLookAtEntityGoal` | 玩家，8 格 | 看人；好奇期间让位 |
| 8 | `CalmLookAroundGoal` | — | 随机张望 |

Boss 阶段的走位与远程攻击统一由 `updateBossCombat` 接管，远程 Goal 不再抢路。

### 5.2 口水

| 阶段 | 伤害 | 穿甲 | 击退 | 射程 |
| --- | --- | --- | --- | --- |
| 1 | 3 | 0 | 无 | 6~24 |
| 2 | 4 | 0 | 0.9（I） | 6~16 |
| 3 | 5 | 3 | 1.4（II） | 6~16 |
| 4 | 6 | 4 | 1.4（II） | 6~16 |
| 4 随机档 | 8 | 0 | 1.4（II） | 17~24 |

- 弹体：自有实体类型 `hush:yao_spit`（`ModEntities.YAO_SPIT`）+ `YaoYongshouSpitEntity`；发射时写入伤害/穿甲/击退，渲染复用原版 `LlamaSpitEntityRenderer`。
- 弹道：出膛点 `(x, eyeY - 0.1, z)`；速度 2.5，散布 0.4；重力补偿 `0.5 × 0.06 × t²`（`t = 水平距离 / 2.5`）。
- 音效：`hush:entity.yao_yongshou.spit`。
- 伤害类型 `hush:yao_spit`（`scaling: never`）；穿甲部分由 `YaoArmorPiercingDamageSource` + `LivingEntityArmorMixin` 处理。
- 无效目标：发射者、同类、非 `LivingEntity`；击退无论伤害是否生效都结算。

### 5.3 近战

- `CloseMeleeGoal` 优先级 2，`canStart` 与 `shouldContinue` 都卡 5 格。
- 伤害类型 `hush:yao_melee`（`scaling: never`，和平难度同样生效），抗性/盾牌照常结算。
- 阶段 3/4 的部分伤害走 `YaoArmorPiercingDamageSource`；穿甲点数不参与护甲减伤，其余照常。
- 命中后向上顶 `0.4 × (1 - 击退抗性)`；挥击动画与 `dust_pillar` 按挥击触发，不要求伤害结算成功。

### 5.4 声波

- 触发：阶段 1 愤怒 100 且目标在 24 格内；阶段 2+ 硬锁且目标进入声波区间。
- 节拍：0 tick 播放蓄力音；34 tick 开炮；160 tick 归零进入下一轮。
- 伤害 6/8/10/14，伤害源 `minecraft:sonic_boom`（`scaling: always`，全额无视护甲，和平难度归零）。
- 粒子沿射线每格 1 个 `SONIC_BOOM`；音效 `hush:entity.yao_yongshou.sonic_boom`。
- 击退：水平 `2.5 × (1 - 击退抗性)`、竖直 `0.5 × (1 - 击退抗性)`，仅伤害结算成功时生效。
- 声波蓄力开始后必须先把这一发打完（近战让路）；阶段 4 的 17~24 随机档声波是立即开炮，不蓄力。

### 5.5 挨打反击（仅阶段 1）

- `damage()` 结算成功且攻击者为非同类 `LivingEntity`：`revengeMode = true`、`revengeShots = 3`、`revengeDeadline = now + 100`、锁定攻击者。
- 每次 `shootAt`/`tryAttack` 命中扣 1；扣到 0 或超时清空攻击者与目标。
- 进入声波、变身 Boss 都会清掉反击状态。

## 6. 数值总表

| 常量 | 值 | 含义 |
| --- | --- | --- |
| `HEARING_RANGE` | 20 格 | 阶段 1 听觉半径 |
| `MAX_CHASE_RANGE` | 24 格 | 阶段 1 脱锁距离 |
| `PHASE_2_HEALTH_RATIO` / `PHASE_3_HEALTH_RATIO` / `PHASE_4_HEALTH_RATIO` | 0.8 / 0.6 / 0.3 | 阶段阈值 |
| `PHASE_2_GIVE_UP_RANGE` | 32 格 | 阶段 2 脱锁距离 |
| `PHASE_4_CHASE_ONLY_RANGE` | 64 格 | 阶段 4 只追不打的分界 |
| 追击停止距离 | 16 / 16 / 32 格（阶段 2~4） | 阶段 1 由远程 Goal 接近，不查表 |
| `NORMAL_ARMOR` / `PHASE_2_ARMOR` / `PHASE_3_ARMOR` / `PHASE_4_ARMOR` | 5 / 10 / 15 / 20 | 护甲 |
| `ARMOR_TOUGHNESS` | 0 | 护甲韧性 |
| 近战伤害 / 穿甲 | 5/0、8/0、8/4、10/5 | 阶段 1~4 |
| 口水伤害 / 穿甲 / 击退 | 3/0/0、4/0/0.9、5/3/1.4、6/4/1.4 | 阶段 1~4 |
| 阶段 4 随机口水 | 8 点、无穿甲、击退 1.4 | 17~24 格 |
| 声波伤害 | 6 / 8 / 10 / 14 | 阶段 1~4 |
| `SONIC_CHARGE_TICKS` / `SONIC_INTERVAL_TICKS` | 34 / 160 tick | 声波蓄力/循环 |
| `SPIT_COOLDOWN_TICKS` | 34 tick | 口水间隔 |
| `PHASE_4_BUFF_TICKS` / `PHASE_4_BUFF_COOLDOWN_TICKS` | 3600 / 1200 tick | 阶段 4 增益时长/冷却 |
| `BOSS_FORM_HEALTH_RATIO` / `BOSS_FORM_REVERT_TICKS` | 0.8 / 600 tick | Boss 变身/退回 |
| `BOSS_SCALE` / `NORMAL_SCALE` | 2 / 1 | 体型 |
| `OUT_OF_COMBAT_REGEN_TICKS` | 200 tick | 脱战回血 |
| 速度 | 空闲 III；阶段 1 追逐 I；阶段 2+ 追逐 II；阶段 4 >64 格追逐 III | 由 `updateMovementSpeed` 管理 |
| `IDLE_LOOK_RANGE_SQUARED` / `IDLE_KEEP_DISTANCE` / `IDLE_APPROACH_SQUARED` | 400 / 16 / 1024 | 空闲跟随 |
| `IDLE_TELEPORT_DISTANCE_SQUARED` | 2304 | >48 格传送 |
| 传送半径 / 偏角 | 18~22 格 / 35°~145° | 空闲传送 |
| `TELEPORT_ATTEMPTS` / `TELEPORT_RETRY_TICKS` | 6 / 100 tick | 传送尝试与重试 |
| `REVENGE_SHOT_LIMIT` / `REVENGE_TIMEOUT_TICKS` | 3 / 100 tick | 反击 |
| `ANGER_*` | 见 §4.2 | 愤怒参数 |
| `CURIOUS_DURATION_TICKS` | 40 tick | 好奇 |
| `SPEED_TRAIL_PARTICLE_INTERVAL_TICKS` | 4 tick | 速度拖尾 |
| `BOSS_XP_REWARD` | 500 | 掉落经验 |

## 7. 音效与文本

| 事件 ID | 复用的原版事件 | 字幕键 | 中文文案 |
| --- | --- | --- | --- |
| `hush:entity.yao_yongshou.ambient` | `minecraft:entity.llama.ambient` | `subtitles.hush.entity.yao_yongshou.ambient` | 姚永寿：吼叫 |
| `hush:entity.yao_yongshou.hurt` | `minecraft:entity.llama.hurt` | `...hurt` | 姚永寿：受伤 |
| `hush:entity.yao_yongshou.death` | `minecraft:entity.llama.death` | `...death` | 姚永寿：死亡 |
| `hush:entity.yao_yongshou.spit` | `minecraft:entity.llama.spit` | `...spit` | 姚永寿：吐口水 |
| `hush:entity.yao_yongshou.sonic_charge` | `minecraft:entity.warden.sonic_charge` | `...sonic_charge` | 姚永寿：蓄力 |
| `hush:entity.yao_yongshou.sonic_boom` | `minecraft:entity.warden.sonic_boom` | `...sonic_boom` | 姚永寿：音波尖啸 |

死亡信息：`death.attack.yao_spit` → `%1$s 被 %2$s 的口水淹死了`；`death.attack.yao_melee` → `%1$s 被 %2$s 一巴掌拍死了`。

## 8. 资源契约

| 文件 | 作用 |
| --- | --- |
| `assets/hush/sounds.json` | 6 个自定义事件，全部 `{"type": "event"}` 转发原版事件 |
| `assets/hush/lang/zh_cn.json` / `en_us.json` | 实体名、物品名、字幕、死亡信息 |
| `data/hush/damage_type/yao_spit.json` | 口水伤害类型（`scaling: never`） |
| `data/hush/damage_type/yao_melee.json` | 近战伤害类型（`scaling: never`） |
| `assets/hush/models/item/yao_yongshou_spawn_egg.json` | 平面刷怪蛋模型（无 parent、无厚度） |
| `assets/hush/textures/item/yao_yongshou_spawn_egg.png` | 刷怪蛋贴图 |

## 9. 不变量

1. `targetSelector` 始终为空；反击只由 `damage()` 计数。
2. 同类永不被 `setTarget` 接受，口水永不伤害同类。
3. 阶段 1 的远程手段由愤怒档位决定：警觉吐口水、100 激怒只在 17~24 格放声波；6~16 格仍吐口水。
4. 5 格内不生成口水（所有阶段）；声波蓄力开始后必须先打完这一发。
5. 阶段 2+ 完全不听声音；阶段 2 超 32 格脱锁，阶段 3/4 永不脱锁。
6. 阶段 4 的 17~24 格随机档：75% 口水 8 点、25% 声波 12 点，均带击退。
7. 不存在幼年形态：`isBaby()` false、`getBreedingAge()` 0、`createChild()` null。
8. 音效事件必须用 `{"type": "event"}` 转发；刷怪蛋模型不能写 `"item/generated"` parent。
9. 口水与近战的伤害类型必须 `scaling: never`；声波用 `minecraft:sonic_boom`。
10. 部分穿甲只能由 `YaoArmorPiercingDamageSource` + `LivingEntityArmorMixin` 实现：入参先减穿甲值，护甲结算后加回；不能拆成两次 `damage()`（会触发无敌帧）。
11. Boss 形态退回只在连续 600 tick 无目标时发生；退回后血量仍 <80% 时必须重新获得目标才再次变身。
12. 护甲每 tick 按阶段重算：5/10/15/20，韧性恒 0；攻击力同步为近战总伤害。
13. 脱战回血只在玩家 200 tick 未造成伤害且未满血时挂生命恢复 I；满血或被再次打中立即移除。
14. 听觉只有一个愤怒入口：振动为主，音效只补没有 GameEvent 的声音（2 tick 配对）。
15. 愤怒每玩家独立 0~100、每 20 tick -1；声波进入 100、退出 `<90`。
16. 好奇只在阶段 1 冷静且无目标时触发：40 tick、40 tick 冷却；目标/声波/Boss 形态立即停止。
17. `Vibrations` 必须在服务端每 tick 推进 `Vibrations.Ticker.tick`，并在 `updateEventHandler` 交给回调。
18. 阶段 4 额外增益由「跌破 30%」事件触发，冷却 1200 tick，可重复；不能每 tick 重刷。

## 10. 验收用例

| 编号 | 场景 | 操作 | 期望 |
| --- | --- | --- | --- |
| A1 | 阶段 1 听觉 | 20 格内走路 10 秒 | 只好奇转头，不锁定 |
| A2 | 警觉 | 5 秒挖 3 个方块 | 锁定并吐口水（3 点） |
| A3 | 阶段 1 脱锁 | 跑出 24 格 | 放弃追击 |
| A4 | 阶段 1 声波 | 5 秒挖 9 个方块并站在 20 格 | 只放声波（6 点），17 格内改吐口水 |
| A5 | 护甲 | 依次打到 70/50/20 血 | 护甲 10/15/20 |
| A6 | 阶段 2 脱锁 | 80%~60% 时跑出 32 格 | 放弃目标；回 32 格内重新锁定 |
| A7 | 阶段 3 锁定 | 打到 50% 后跑出 100 格 | 仍锁定并追击，不脱锁 |
| A8 | 阶段 4 锁定 | 打到 25% 后跑出 100 格 | 仍锁定，>64 格只追不打、速度更快 |
| A9 | 近战伤害 | 各阶段贴脸 | 5 / 8 / 8 / 10 点；阶段 3 有 4 点穿甲、阶段 4 有 5 点 |
| A10 | 口水伤害 | 各阶段 10 格 | 3 / 4 / 5 / 6 点；阶段 3/4 分别 3/4 点穿甲 |
| A11 | 击退档 | 各阶段口水命中 | 阶段 1 无击退，阶段 2 击退 I，阶段 3/4 击退 II |
| A12 | 阶段 4 随机档 | 17~24 格反复挨打 | 约 3:1 出现口水 8 点 / 声波 12 点 |
| A13 | 声波伤害 | 各阶段站在声波段 | 6 / 8 / 10 / 14 点，全额无视护甲 |
| A14 | 阶段 4 增益 | 从 31% 打到 29% | 获得 5 种 3 分钟增益 + 图腾粒子 |
| A15 | 阶段 4 冷却 | 1 分钟内反复跌破 30% | 只触发一次；1 分钟后再跌破可再触发 |
| A16 | 回血降档 | 阶段 3 回血到 70% | 护甲回 10、力量消失；仍是 Boss 形态 |
| A17 | 退回普通 | 80%~60% 脱锁 30 秒 | 体型 1 倍、护甲 5、血条收起；再被打重新变身 |
| A18 | 阶段 1 不回听声 | 阶段 2+ 在 20 格内挖方块 | 目标与攻击不变 |
| A19 | 反击上限 | 阶段 1 打它一下后不还手 | 最多还手 3 次即停 |
| A20 | 刷怪蛋 | 对地面与对实体使用 | 只生成成年体 |
| A21 | 同类 | 两只贴在一起 | 不互殴、口水不伤同类 |
| A22 | 和平难度 | 和平难度下被口水/近战命中 | 仍造成对应伤害（穿甲部分照样生效） |
| A23 | 创造模式近战 | 创造模式贴身被打 | 不掉血，但播放抡臂与尘柱 |
| A24 | 死亡脱战 | 目标被击杀 | 当 tick 清目标与攻击状态 |
| A25 | 掉落经验 | 玩家击杀 | 500 点经验 |
| A26 | 常驻 | 跑远再回来 | 实体仍在原地 |
| A27 | 空闲距离 | 召唤后跑出 40 格 | 20 格内注视、16~32 格站定、>48 格传送侧前方约 20 格 |
| A28 | 透明穿甲 | 高护甲目标挨阶段 3 近战 | 4 点不受护甲影响，其余 4 点照常减伤 |
| A29 | Boss 血条 | 阶段 2/3/4 | 顶部血条随血量下降；阶段 4 紫色 +「（狂暴）」 |
| A30 | 改名字幕 | 触发各类音效 | 字幕均为「姚永寿：…」，ID 前缀为 `hush` |

## 11. 已知限制

- 刷怪蛋是单面平面模型：侧视是一条线，掉落物转到背面看不到图案。
- 创造模式玩家免疫伤害：口水只击退；近战与声波不结算伤害，近战的动作与尘柱照常播放，声波只剩粒子与音效。
- 声波沿用 `minecraft:sonic_boom`（`scaling: always`），和平难度归零。
- 部分穿甲通过 Mixin 修改护甲结算；与重定向 `LivingEntity#applyArmorToDamage` 的模组可能冲突。
- 听觉只统计玩家；振动按距离延迟结算，最远 20 格时最多晚 1 秒；待处理振动不写 NBT。
- 音效补充靠 2 tick 配对，间隔超过 2 tick 时可能重复计一次或漏计。
- 阶段 4 的「狂暴」只改血条表现，不改攻速与伤害。
- 改名后旧存档中的 `firstmob:` 实体不再注册，需要重新召唤。

## 12. 生成方式（已定：结构生成，未实现）

当前状态：只能通过刷怪蛋或 `/summon` 获得。实体类型注册为 `SpawnGroup.MISC`，构造时调用 `setPersistent()`，所以既不自然生成，也不会自然消失。

目标（已定，本版仍不实现）：后续以「结构生成」出现，随结构一次性生成一只；结构本体暂定为小型遗迹 `hush:shrine`，由数据包提供。

选择结构生成的理由：

- 它是单体 Boss（100 点生命、四阶段），不该混进夜晚自然刷怪与怪物上限。
- 结构天然具备「每个结构实例一只」的语义，不需要额外记录已生成坐标。
- 不改 `SpawnGroup`、不接 `SpawnRestriction`，原版刷怪、怪物上限、`/kill @e[type=monster]` 都不受影响。
- 与 A26「常驻、不自然消失」一致。

被否掉的方案：条件生成（Boss 随机刷在玩家附近，难度不可控，还要改 `SpawnGroup` 与上限）；定点触发（依赖命令方块或进度，只适合演示，不适合发布）。

### 12.1 决策

| 问题 | 决定 | 说明 |
| --- | --- | --- |
| 唯一性 | 每个结构实例一只，结构不重复生成 | 不按玩家距离刷新；同一结构被 `/place` 多次，每份各一只 |
| 持久化 | 保持 `setPersistent()` | 不自然消失；数量由结构数量封顶，不会随时间堆积 |
| SpawnGroup | 继续用 `MISC` | 不参与怪物上限、自然刷怪与怪物清理 |
| 和平难度 | 照常生成，不额外处理 | 与 A22 一致：和平下仍结算伤害，声波为 0 |
| 听觉初始 | 与 `/summon` 相同 | 阶段 1 听觉与好奇照常生效，不因生成来源给初始愤怒 |
| 结构本体 | 暂定 `hush:shrine` 小型遗迹，数据包提供 | 外观、战利品、群系与稀有度实现时补；不改任何战斗数值 |

### 12.2 实现前置（未做）

- 结构 NBT、结构集与生物群系标签放在数据包，不塞进模组 jar：换版本不动存档，也不扩大 jar。
- 结构模板里用实体占位，NBT 至少带 `PersistenceRequired:1b`；模板内**去掉 UUID**，生成时再分配，避免复制出重复 UUID。
- 生成代码放在 `com.example.hush.spawn`（新包），实体类不感知自己的生成来源。
- 战斗数值与阶段仍只由 `CombatProfile` 决定，生成方案不改变任何数值。

### 12.3 验收（实现后）

| 编号 | 用例 | 期望 |
| --- | --- | --- |
| G1 | `/place structure hush:shrine` 一次 | 恰好一只；血量与阶段按现有规则 |
| G2 | 卸载并重载区块、重启服务器 | 实体仍在，血量与阶段按存档重算，不回血 |
| G3 | 连续放置两个结构 | 每份各一只、互不共享血量，不额外复制 |
| G4 | 自然夜晚挂机 | 不额外自然生成；怪物上限与清理行为不变 |
| G5 | 和平难度放置并挨打 | 与 A22 一致：伤害照常，声波为 0 |
| G6 | 用结构方块二次粘贴 | 每份独立，UUID 不重复 |
