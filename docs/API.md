# 接口文档（API）

面向二次开发与维护：本模组对外暴露了什么、这些接口的契约是什么、哪些可以随便改。
行为与数值规则见 [SPEC.md](SPEC.md)，工程约定见 [AGENTS](../AGENTS.md)。

## 1. 模块标识

| 项 | 值 |
| --- | --- |
| Mod ID | `firstmob` |
| Maven 坐标 | `com.example.firstmob:firstmob`（版本取自 `gradle.properties` 的 `version`） |
| 入口类（main） | `com.example.firstmob.Firstmob` |
| 入口类（client） | `com.example.firstmob.client.FirstmobClient` |
| Mixin 配置 | `firstmob.mixins.json`（由 `fabric.mod.json` 的 `mixins` 引用） |
| Java 包根 | `com.example.firstmob` |
| 支持版本 | Minecraft 1.21.1 / Fabric Loader ≥ 0.20.1 / Java ≥ 21 / Fabric API `*` |

## 2. 注册表 ID

### 2.1 实体与物品

| 注册表 | ID | Java 常量 | 说明 |
| --- | --- | --- | --- |
| `Registries.ENTITY_TYPE` | `firstmob:yao_yongshou` | `ModEntities.YAO_YONGSHOU` | 生成组 `MISC`，碰撞箱 0.6 × 1.95（Boss 形态缩放 2 倍后 1.2 × 3.9），眼高 1.62，追踪范围 10；默认属性：`max_health = 100`、`scale = 1`、`armor = 5`、`armor_toughness = 0`（Boss 形态运行时改为 `scale = 2`、`armor = 20`；Boss 且 <20% 血时 10/5） |
| `Registries.ITEM` | `firstmob:yao_yongshou_spawn_egg` | `ModItems.YAO_YONGSHOU_SPAWN_EGG` | `SpawnEggItem`，蛋色 `0x8A6D3B` / `0x5C4033`，进「刷怪蛋」创造页签 |

### 2.2 音效事件

均在 `ModSounds` 中注册，类型为 `SoundEvent`，实际声音文件通过 `assets/firstmob/sounds.json` 转发原版事件。

| ID（省略前缀 `firstmob:`） | Java 常量 | 转发到 |
| --- | --- | --- |
| `entity.yao_yongshou.ambient` | `YAO_AMBIENT` | `minecraft:entity.llama.ambient` |
| `entity.yao_yongshou.hurt` | `YAO_HURT` | `minecraft:entity.llama.hurt` |
| `entity.yao_yongshou.death` | `YAO_DEATH` | `minecraft:entity.llama.death` |
| `entity.yao_yongshou.spit` | `YAO_SPIT` | `minecraft:entity.llama.spit` |
| `entity.yao_yongshou.sonic_charge` | `SONIC_CHARGE` | `minecraft:entity.warden.sonic_charge` |
| `entity.yao_yongshou.sonic_boom` | `SONIC_BOOM` | `minecraft:entity.warden.sonic_boom` |

### 2.3 伤害类型

| 注册表 | ID | Java 常量 | 文件 |
| --- | --- | --- | --- |
| `RegistryKeys.DAMAGE_TYPE` | `firstmob:yao_spit` | `YaoYongshouSpitEntity.DAMAGE_TYPE` | `data/firstmob/damage_type/yao_spit.json` |
| `RegistryKeys.DAMAGE_TYPE` | `firstmob:yao_melee` | `YaoYongshouEntity.MELEE_DAMAGE_TYPE` | `data/firstmob/damage_type/yao_melee.json` |

字段语义见 [§5.2](#52-damage_type)。

## 3. Java 接口

### 3.1 `Firstmob`

```java
public class Firstmob implements ModInitializer {
    public static final String MOD_ID = "firstmob";
    public static final Logger LOGGER;          // org.slf4j.Logger，名字即 mod id
    public static Identifier id(String path);   // 等价于 Identifier.of("firstmob", path)
    public void onInitialize();                 // 注册实体、物品、音效，加入创造页签
}
```

`onInitialize()` 按 `ModEntities` → `ModItems` → `ModSounds` 顺序注册；`ModItems` 的静态字段引用 `ModEntities.YAO_YONGSHOU`，调整注册顺序时注意类初始化依赖。

### 3.2 `YaoYongshouEntity`

`public class YaoYongshouEntity extends LlamaEntity`

#### 构造与静态入口

| 成员 | 签名 | 契约 |
| --- | --- | --- |
| 构造函数 | `YaoYongshouEntity(EntityType<? extends LlamaEntity> type, World world)` | 由 `EntityType` 工厂调用，不要直接 `new` |
| 音效入口 | `public static void onPlayerSound(PlayerEntity player, SoundEvent sound)` | **仅服务端有效**；由 `PlayerSoundMixin` 在 `playSound` 的 HEAD 调用；内部过滤客户端、死亡、旁观者与 `.death` |
| 动作入口 | `public static void onPlayerAction(PlayerEntity player)` | 同上；由 `PlayerInteractionMixin` 在挖掘/交互方块时调用，记一次 OTHER |

两个静态入口都对 20 格内**所有**姚永寿广播，并各自独立记账；重复调用会重复计数，接入新的事件源时必须保证"一次动作只调一次"。

#### 覆写方法

| 方法 | 签名 | 契约 |
| --- | --- | --- |
| `tick` | `public void tick()` | 服务端负责目标死亡脱战、反击超时、25 格放弃、声波推进、停火判定 |
| `setTarget` | `public void setTarget(LivingEntity target)` | 目标是同类时直接忽略；锁定新目标瞬间尝试首发（见 SPEC §5.2） |
| `damage` | `public boolean damage(DamageSource source, float amount)` | 结算成功后接管反击/声波换目标；返回父类结果 |
| `shootAt` | `public void shootAt(LivingEntity target, float pullProgress)` | 客户端、`sonicMode`（非 Boss 的声波替代口水）、近战距离内（3 / 5 格）直接返回；发射口水并扣反击/保证计数 |
| `tryAttack` | `public boolean tryAttack(Entity target)` | 用 `firstmob:yao_melee`（`scaling: never`）结算 15 点近战伤害，和平难度也生效；挥击动画与尘柱按挥击触发（不依赖伤害是否结算成功），命中后按铁傀儡的方式向上顶目标（`0.4 × (1 - 击退抗性)`）并扣反击/保证计数 |
| 近战伤害类型 | `public static final RegistryKey<DamageType> MELEE_DAMAGE_TYPE` | `firstmob:yao_melee`，数据文件必须存在；`scaling` 必须为 `never` |
| `interactMob` | `public ActionResult interactMob(PlayerEntity player, Hand hand)` | 恒返回 `PASS`，拒绝喂食、驯服、上鞍等交互 |
| `createChild` | `public LlamaEntity createChild(ServerWorld world, PassiveEntity partner)` | 恒返回 `null`，不产生后代 |
| `canBreedWith` | `public boolean canBreedWith(AnimalEntity other)` | 恒 false |
| `isBreedingItem` | `public boolean isBreedingItem(ItemStack stack)` | 恒 false |
| `isBaby` / `setBaby` | `public boolean isBaby()` / `public void setBaby(boolean baby)` | 恒 false / 空实现 |
| `getBreedingAge` / `setBreedingAge` | `public int getBreedingAge()` / `public void setBreedingAge(int age)` | 恒 0 / 空实现 |
| `getXpToDrop` | `protected int getXpToDrop()` | 恒 500，Boss 经验 |
| 攻击动画 | `public float getAttackAnimationProgress()`；`tryAttack` 命中时把 `ATTACK_ANIM` 设为 10，每 tick 递减 | 0 = 待机，0~1 = 挥击进度；数据同步（`TrackedData<Integer>`），客户端模型据此播放抡臂 |
| `shootAt` 冷却 | 内部字段 `nextSpitTick` | `shootAt` 自行限流：基础 34 tick，`<20%` 血时 27 tick；锁定模式与 `ProjectileAttackGoal` 共用 |
| `updateLockMode`（私有） | 每 tick 一次 | `<60%` 血时接管目标与攻击：优先打它的玩家，其次最近玩家；<5 格近战、5~20 格口水、20~40 格声波；>40 格只追不打 |
| `updateIdleFollow`（私有） | 每 tick 一次（仅 `IDLE`） | 20 格内 `lookAt`、>32 格靠近、16~32 格站定不动、<16 格后退、>48 格调用 `tryTeleport` |
| `IdleWanderGoal`（私有内部类） | `WanderAroundFarGoal` 子类 | 玩家在 16~32 格内时 `canStart` / `shouldContinue` 返回 false，保证"合适距离内只站着看人" |
| `updateMovementSpeed` / `setSpeedLevel`（私有） | 每 tick 一次 | 游走速度 III（amplifier 2）、战斗追击速度 II、其余按 Boss 形态速度 I 或普通形态无加速 |
| `updateFormAttributes` / `updateBossFormRevert`（私有） | 每 tick 一次 | 护甲/韧性按形态与血量重算：普通 5/0、Boss 20/0、Boss 且 <20% 血 10/5；Boss 形态（非锁定）连续 600 tick 无目标时退回普通形态 |
| `updateOutOfCombatRegen`（私有） | 每 tick 一次 | 玩家连续 200 tick 未造成伤害且未满血时挂生命恢复 I（无限时长），满血或再次被打中立即移除；只移除本机制挂的无限恢复 I |
| `teleportNearPlayer`（私有） | 每次最多 6 个随机落点 | 相对玩家朝向随机偏 35°~145°（左右随机、**不含正前方**）、半径 18~22 格，落点要求 3×3×4 空地 |
| `teleportFarFromPlayer`（私有） | 玩家周围无落点时的退路 | 任意方向 48~64 格；之后 `TELEPORT_RETRY_TICKS`（100 tick = 5 秒）再试 |
| `nearestPlayer`（私有） | 供锁定与空闲跟随共用 | 同世界最近的存活、非旁观玩家 |
| `onStartedTrackingBy` / `onStoppedTrackingBy` | `public void onStartedTrackingBy(ServerPlayerEntity player)` / `public void onStoppedTrackingBy(ServerPlayerEntity player)` | 把玩家加进 / 移出 Boss 血条，与凋灵同一套写法 |
| `onDeath` | `public void onDeath(DamageSource source)` | 服务端收起 Boss 血条并清空玩家列表 |
| `writeCustomDataToNbt` / `readCustomDataFromNbt` | `public void writeCustomDataToNbt(NbtCompound nbt)` / `public void readCustomDataFromNbt(NbtCompound nbt)` | 存档 `BossForm`（是否处于 Boss 形态）、`BossFormDormant`（是否已退回普通形态、等目标再变身）与 `LowHealthTriggered`（本轮濒死效果是否已结算）；读档时补回 2 倍体型与永久力量/抗性 |
| 音效 | `protected SoundEvent getAmbientSound()`、`getHurtSound(DamageSource)`、`getDeathSound()` | 返回 `ModSounds` 中的自定义事件 |

形态相关的 `BossForm` / `BossFormDormant` / `LowHealthTriggered` 三个 NBT 键属于**存档契约**：删掉会导致读档后 Boss 退回普通形态、已退回的 Boss 又自动变身、或濒死效果重复触发。战斗状态字段（`noiseTarget`、`sonicMode` 等）仍然不写 NBT。

Boss 血条是实例字段 `private final ServerBossBar bossBar`（红色 `PROGRESS`，标题取实体显示名），不进 NBT：存档重新加载时会重新创建并重新分发给追踪者。

状态字段（`noiseTarget`、`guaranteedShots`、`revengeMode`、`sonicMode` 等）全部私有，**不写入 NBT**，不构成存档契约。跨存档/跨版本时不保证行为状态延续。

形态与战斗的粒子效果（`dust_color_transition`、`totem_of_undying`、`firework`、`dust_pillar`）全部在服务端生成，不新增注册项或资源；数量、间隔与判定见 [SPEC §3.6](SPEC.md#36-粒子效果纯表现)。

#### 调试接口

`YaoYongshouEntity` 内私有常量 `DEBUG`（当前为 `true`）控制诊断日志，格式为 `[Yao#<实体 id>] <事件> ...`，事件包括 `TRIGGER`、`RELEASE`、`DAMAGE`、`SHOOT`、`SONIC enter`、`SONIC exit`、`BOSS form enter`、`BOSS form exit`、`FORM`、`LOW`、`REGEN on`、`REGEN off`。发布版本前改为 `false` 并重新构建。

### 3.3 `YaoYongshouSpitEntity`

`public class YaoYongshouSpitEntity extends LlamaSpitEntity`

| 成员 | 签名 | 契约 |
| --- | --- | --- |
| 伤害类型键 | `public static final RegistryKey<DamageType> DAMAGE_TYPE` | `firstmob:yao_spit`，数据文件必须存在 |
| 构造函数 | `YaoYongshouSpitEntity(EntityType<? extends LlamaSpitEntity> type, World world)` | 由 `EntityType.LLAMA_SPIT` 创建 |
| 命中 | `protected void onEntityHit(EntityHitResult result)` | 客户端直接返回；命中同类/发射者/非生物不结算；伤害 4 + 水平击退 0.9 |

弹体没有自定义 `EntityType`，复用 `EntityType.LLAMA_SPIT`；因此不能通过实体类型区分口水来源，判断归属请用 `getOwner()`。

### 3.4 注册类

| 类 | 公开成员 | 说明 |
| --- | --- | --- |
| `ModEntities` | `public static final EntityType<YaoYongshouEntity> YAO_YONGSHOU`；`public static void register()` | 注册类型与默认属性（羊驼属性 + 100 血量 + 普通形态 1 倍体型/5 护甲/0 护甲韧性 + 15 攻击力 + 0 攻击击退）；Boss 形态的 2 倍体型由实体在变身时写入，护甲/韧性每 tick 按形态与血量重算 |
| `ModItems` | `public static final Item YAO_YONGSHOU_SPAWN_EGG`；`public static void register()` | 注册刷怪蛋 |
| `ModSounds` | 6 个 `SoundEvent` 常量；`public static void register()` | 静态字段完成注册，`register()` 只为触发类加载 |

三个类的注册方法均幂等（重复调用不会重复注册），但只在主入口调用一次。

### 3.5 客户端

| 类 | 说明 |
| --- | --- |
| `FirstmobClient` | `onInitializeClient()` 中注册 `YaoYongshouRenderer` |
| `YaoYongshouRenderer` | `MobEntityRenderer<YaoYongshouEntity, YaoYongshouModel>`，阴影 0.5，贴图 `minecraft:textures/entity/zombie/zombie.png`，模型层 `EntityModelLayers.ZOMBIE` |
| `YaoYongshouModel` | `BipedEntityModel<YaoYongshouEntity>`；待机调用 `CrossbowPosing.meleeAttack(leftArm, rightArm, false, ...)` 得到僵尸双臂前伸；`getAttackAnimationProgress() > 0` 时改用三段插值的抡臂（抬起→抡下→回位） |

渲染器只影响观感，删除/替换不影响服务端逻辑；但客户端必须能解析该实体类型，否则会回退为默认渲染。

### 3.6 Mixin

| Mixin 类 | 目标类 | 方法（Yarn 签名） | 注入点 | 作用 |
| --- | --- | --- | --- | --- |
| `PlayerSoundMixin` | `PlayerEntity` | `playSound(Lnet/minecraft/sound/SoundEvent;FF)V` | `HEAD` | 把玩家音效转给 `onPlayerSound`；两端都会注入，服务端内部过滤 |
| `PlayerInteractionMixin` | `ServerPlayerInteractionManager` | `tryBreakBlock(Lnet/minecraft/util/math/BlockPos;)Z` | `HEAD` | 挖掘动作记一次 OTHER |
| `PlayerInteractionMixin` | `ServerPlayerInteractionManager` | `interactBlock(Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/world/World;Lnet/minecraft/item/ItemStack;Lnet/minecraft/util/Hand;Lnet/minecraft/util/hit/BlockHitResult;)Lnet/minecraft/util/ActionResult;` | `HEAD` | 方块交互记一次 OTHER |

配置契约（`firstmob.mixins.json`）：

```json
{
  "required": true,
  "package": "com.example.firstmob.mixin",
  "compatibilityLevel": "JAVA_21",
  "mixins": ["PlayerSoundMixin", "PlayerInteractionMixin"],
  "injectors": { "defaultRequire": 1 }
}
```

Loom 1.15 在 `remapJar` 阶段会把方法名重映射为 intermediary，**不需要 refmap**；手写 `"refmap"` 会因文件不存在而报错。

## 4. 调用时序

```
玩家 playSound(...) ──HEAD──▶ PlayerSoundMixin ──▶ onPlayerSound
                                                    │ 过滤（客户端/死亡/旁观者/.death）+ 分类
                                                    ▼
                                   dispatch：20 格内所有 YaoYongshouEntity
                                                    │
                                                    ▼
                                   hearSoundFrom：按 UUID 记账 / 限频
                                       │                            │
                         loud ≥ 9 ─────┘                            └───── loud ≥ 3
                               ▼                                          ▼
                       enterSonicMode                          setTarget(player) → 首发 shootAt
```

挖掘/交互不走 `playSound`，由 `PlayerInteractionMixin` 直接调用 `onPlayerAction` → `dispatch`（按 OTHER 处理）。

`setTarget` 内部可能同步调用 `shootAt`（首发），因此锁定目标的那一帧就可能生成口水；接入自定义目标逻辑时不要假设"先锁定、下一 tick 才攻击"。

## 5. 资源与数据契约

### 5.1 `sounds.json`

位置 `assets/firstmob/sounds.json`，每个键是事件 ID 去掉命名空间后的 path：

```json
{
  "entity.yao_yongshou.spit": {
    "subtitle": "subtitles.firstmob.entity.yao_yongshou.spit",
    "sounds": [
      { "name": "minecraft:entity.llama.spit", "type": "event" }
    ]
  }
}
```

约束：

- `subtitle` 必须指向语言文件里存在的键，否则字幕显示原始键名。
- 复用其他命名空间的事件时**必须**写 `"type": "event"`；直接写 `minecraft:entity.llama.spit` 会被当成同命名空间下的 `.ogg` 路径，解析为**空事件**（日志表现为 `Unable to play empty soundEvent`）。
- 事件 ID、字幕键与 [SPEC §7](SPEC.md#7-音效与文本) 的表保持同步。

### 5.2 `damage_type`

位置 `data/firstmob/damage_type/yao_spit.json`（口水）与 `yao_melee.json`（近战），字段相同：

```json
{
  "message_id": "yao_spit",
  "exhaustion": 0.1,
  "scaling": "never"
}
```

| 字段 | 值 | 说明 |
| --- | --- | --- |
| `message_id` | `yao_spit` / `yao_melee` | 死亡信息键为 `death.attack.<message_id>`；缺失会显示原始键 |
| `exhaustion` | 0.1 | 受击者的饥饿消耗 |
| `scaling` | `never` | **必须**；否则 `PlayerEntity#damage` 会按难度清零，和平难度下口水与近战都无效 |

新增伤害类型时同步：`RegistryKey` 常量 → 数据文件 → `lang/*.json` 的 `death.attack.<message_id>`。

### 5.3 语言文件

位置 `assets/firstmob/lang/zh_cn.json` 与 `en_us.json`，键必须成对出现：

| 键 | 中文示例 |
| --- | --- |
| `entity.firstmob.yao_yongshou` | 姚永寿 |
| `item.firstmob.yao_yongshou_spawn_egg` | 姚永寿刷怪蛋 |
| `subtitles.firstmob.entity.yao_yongshou.*` | 姚永寿：吼叫 / 受伤 / 死亡 / 吐口水 / 蓄力 / 音波尖啸 |
| `death.attack.yao_spit` | `%1$s 被 %2$s 的口水淹死了` |
| `death.attack.yao_melee` | `%1$s 被 %2$s 一巴掌拍死了` |

### 5.4 模型与贴图

刷怪蛋模型 `assets/firstmob/models/item/yao_yongshou_spawn_egg.json`：

- **不能**写 `"parent": "item/generated"`：会走 `builtin/generated`，按贴图 alpha 生成 1/16 厚的挤压壳，自定义 `elements` 被忽略（表现为重影/厚度）。
- 当前做法：无 `parent`，内联 `display` 与 `gui_light`，单个元素 `from [0,0,7.5] to [16,16,7.5]`，`north`/`south` 两个面共用同一 UV，最终 **2 个共面 quad**。
- 贴图路径 `firstmob:item/yao_yongshou_spawn_egg`，源图见 `素材/yao_yongshou.png`。

实体渲染贴图直接引用原版僵尸贴图，不占用本模组资源。

## 6. 稳定度与兼容性

| 级别 | 内容 | 变更规则 |
| --- | --- | --- |
| **稳定** | 实体/物品/伤害类型/音效事件 ID、字幕键、死亡信息键、入口类名、Mixin 配置文件名 | 改名或移除属于破坏性变更，必须升 minor 并在 CHANGELOG 标注 |
| **实验** | `onPlayerSound` / `onPlayerAction` 静态入口、公开覆写方法的时序、刷怪蛋模型、渲染器、属性与行为数值 | 可以调整，需同步 SPEC/API |
| **内部** | 私有字段与常量、Goal 组合、日志文案、`DEBUG` 开关 | 可自由改动，不影响外部 |

兼容性说明：

- 仅支持 Minecraft **1.21.1**；升级游戏版本需要重新核对 `LlamaEntity`、`ProjectileAttackGoal`、`ServerPlayerInteractionManager` 的签名。
- 依赖 Fabric API 的 `FabricDefaultAttributeRegistry`、`ItemGroupEvents`、`EntityRendererRegistry`。
- 不提供配置文件与网络包；所有行为由编译期常量决定。

## 7. 扩展指南

### 7.1 新增音效事件

1. `ModSounds` 增加一行 `register("entity.yao_yongshou.<name>")` 常量声明。
2. `assets/firstmob/sounds.json` 增加条目，`sounds[].type` 写 `event`。
3. `lang/zh_cn.json` 与 `en_us.json` 增加 `subtitles.firstmob.entity.yao_yongshou.<name>`。
4. 在实体里用 `this.playSound(...)` 或 `world.playSound(...)` 播放，并同步 SPEC §7。

### 7.2 调整行为数值

常量都在 `YaoYongshouEntity` 顶部（`SOUND_THRESHOLD`、`SONIC_RANGE` 等），改完后：

1. 更新 [SPEC §6 数值总表](SPEC.md#6-数值总表)。
2. 按 [SPEC §10 验收用例](SPEC.md#10-验收用例) 回归。
3. 在 CHANGELOG 的「调整」下记录。

### 7.3 新增 Goal

- `initGoals()` 已经清空 `goalSelector` 与 `targetSelector`，新 Goal 直接 `add` 即可。
- **不要**给 `targetSelector` 加回 `RevengeGoal` 系目标：会和 `damage()` 里的反击计数冲突。
- 远程 Goal 必须自己处理"≤3 格不射击"，或复用 `shootAt` 的距离守卫。

### 7.4 新增生物

按 `YaoYongshou` 的清单复制：

1. `ModEntities` 注册 `EntityType` 与属性。
2. 实体类 + （可选）自定义弹体。
3. `FirstmobClient` 注册渲染器。
4. `lang` 增加实体名与刷怪蛋名。
5. （可选）刷怪蛋物品与模型。
6. 同步 README、SPEC、API 与 CHANGELOG。

## 8. 日志与排障

| 现象 | 排查点 |
| --- | --- |
| 字幕显示键名 | `sounds.json` 的 `subtitle` 与 `lang` 键不一致 |
| 日志 `Unable to play empty soundEvent` | `sounds.json` 少了 `"type": "event"`，或事件 ID 写错 |
| 启动报 `InvalidMixinException` | 目标方法签名随版本变化，或 `firstmob.mixins.json` 未更新 |
| 反击停不下来 | `targetSelector` 是否被重新加回 `SpitRevengeGoal`；`damage()` 是否还在计数 |
| 和平难度无伤害 | 伤害类型是否 `scaling: never`（口水/近战都必须是）；是否用了 `minecraft:sonic_boom`（`always`） |
| 刷怪蛋有厚度/重影 | 模型是否误加 `item/generated` parent |

诊断日志开关：`YaoYongshouEntity.DEBUG`；日志文件 `run/logs/latest.log`，行首为 `[Yao#<id>]`。
