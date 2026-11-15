# 接口文档（API）

面向二次开发与维护：本模组对外暴露了什么、这些接口的契约是什么、哪些可以随便改。
行为与数值规则见 [SPEC.md](SPEC.md)，工程约定见 [AGENTS](../AGENTS.md)。

## 1. 模块标识

| 项 | 值 |
| --- | --- |
| Mod ID | `hush` |
| 中文名 | 肃静 |
| Maven 坐标 | `com.example.hush:hush`（版本取自 `gradle.properties` 的 `version`） |
| 入口类（main） | `com.example.hush.Hush` |
| 入口类（client） | `com.example.hush.client.HushClient` |
| Mixin 配置 | `hush.mixins.json`（由 `fabric.mod.json` 的 `mixins` 引用） |
| Java 包根 | `com.example.hush` |
| 支持版本 | Minecraft 1.21.1 / Fabric Loader ≥ 0.20.1 / Java ≥ 21 / Fabric API `*` |

## 2. 注册表 ID

### 2.1 实体与物品

| 注册表 | ID | Java 常量 | 说明 |
| --- | --- | --- | --- |
| `Registries.ENTITY_TYPE` | `hush:yao_yongshou` | `ModEntities.YAO_YONGSHOU` | `MISC`，碰撞箱 0.6 × 1.95（Boss 形态 2 倍后 1.2 × 3.9），眼高 1.62，追踪范围 10；默认 `max_health = 100`、`scale = 1`、`armor = 5`、`armor_toughness = 0`、`attack_damage = 5`、`attack_knockback = 0` |
| `Registries.ITEM` | `hush:yao_yongshou_spawn_egg` | `ModItems.YAO_YONGSHOU_SPAWN_EGG` | `SpawnEggItem`，蛋色 `0x8A6D3B` / `0x5C4033`，进「刷怪蛋」页签 |

### 2.2 音效事件

均在 `ModSounds` 中注册，实际声音通过 `assets/hush/sounds.json` 转发原版事件。

| ID（省略前缀 `hush:`） | Java 常量 | 转发到 |
| --- | --- | --- |
| `entity.yao_yongshou.ambient` | `YAO_AMBIENT` | `minecraft:entity.llama.ambient` |
| `entity.yao_yongshou.hurt` | `YAO_HURT` | `minecraft:entity.llama.hurt` |
| `entity.yao_yongshou.death` | `YAO_DEATH` | `minecraft:entity.llama.death` |
| `entity.yao_yongshou.spit` | `YAO_SPIT` | `minecraft:entity.llama.spit` |
| `entity.yao_yongshou.sonic_charge` | `SONIC_CHARGE` | `minecraft:entity.warden.sonic_charge` |
| `entity.yao_yongshou.sonic_boom` | `SONIC_BOOM` | `minecraft:entity.warden.sonic_boom` |

### 2.3 伤害类型

| ID | Java 常量 | 文件 | 用途 |
| --- | --- | --- | --- |
| `hush:yao_spit` | `YaoYongshouSpitEntity.DAMAGE_TYPE` | `data/hush/damage_type/yao_spit.json` | 口水；`scaling: never` |
| `hush:yao_melee` | `YaoYongshouEntity.MELEE_DAMAGE_TYPE` | `data/hush/damage_type/yao_melee.json` | 近战；`scaling: never` |

部分穿甲不再新增伤害类型：由 `YaoArmorPiercingDamageSource` 携带穿甲值，经 `LivingEntityArmorMixin` 在护甲结算前后拆开处理。

## 3. Java 接口

### 3.1 `Hush`

```java
public class Hush implements ModInitializer {
    public static final String MOD_ID = "hush";
    public static final Logger LOGGER;
    public static Identifier id(String path);
    public void onInitialize();
}
```

`onInitialize()` 按 `ModEntities` → `ModItems` → `ModSounds` 顺序注册，并把刷怪蛋加入创造页签。

### 3.2 `YaoYongshouEntity`

`public class YaoYongshouEntity extends LlamaEntity implements Vibrations`

#### 构造与静态入口

| 成员 | 签名 | 契约 |
| --- | --- | --- |
| 构造函数 | `YaoYongshouEntity(EntityType<? extends LlamaEntity>, World)` | 由 `EntityType` 工厂调用；创建振动回调与 `EntityGameEventHandler` |
| 音效入口 | `public static void onPlayerSound(PlayerEntity, SoundEvent)` | 仅服务端；由 `PlayerSoundMixin` 在 `playSound` HEAD 调用 |
| 振动接口 | `getVibrationListenerData()` / `getVibrationCallback()` | 半径 20、标签 `GameEventTags.WARDEN_CAN_LISTEN` |
| 事件注册 | `updateEventHandler(BiConsumer<EntityGameEventHandler<?>, ServerWorld>)` | 不调用收不到 GameEvent |

#### 覆写方法

| 方法 | 契约 |
| --- | --- |
| `tick()` | 服务端推进振动；每 tick 计算阶段、护甲、增益、Boss 血条；阶段 1 走听觉/愤怒，阶段 2+ 走 `updateBossCombat` |
| `setTarget` | 拒绝同类；锁定新目标瞬间在射程内补一发口水 |
| `damage` | 结算成功后刷新脱战计时；阶段 1 接管反击，阶段 3/4 记忆攻击者 |
| `shootAt` | 客户端/5 格内不发射；阶段 1 的 17~24 格由声波接管 |
| `tryAttack` | 用 `hush:yao_melee` + 阶段穿甲结算；挥击动画与尘柱按挥击触发；命中后向上顶 0.4 |
| `interactMob` / `createChild` / `canBreedWith` / `isBreedingItem` | 拒绝交互与繁殖；`createChild` 返回 null |
| `isBaby` / `getBreedingAge` / `setBaby` / `setBreedingAge` | 恒成年 |
| `getXpToDrop` | 500 |
| `getAttackAnimationProgress(float)` | 0 = 待机，0~1 = 挥击进度，按渲染端 age 插值 |
| `writeCustomDataToNbt` / `readCustomDataFromNbt` | 只存 `BossForm`、`BossFormDormant`；阶段按血量重算 |

#### 阶段与战斗（私有）

| 成员 | 契约 |
| --- | --- |
| `updateFormState()` | 按血量进入 Boss 形态、计算阶段、重算护甲/攻击、维护增益、触发阶段 4 事件 |
| `combatPhaseFor(float)` | `r≥0.8→1`、`r≥0.6→2`、`r>0.3→3`、否则 4；Boss 形态下限 2 |
| `updateBossCombat(long)` | 阶段 2+：解析目标、按追停距离走位、按距离选择近战/口水/声波/随机档 |
| `resolveBossTarget()` | 阶段 3/4 优先上次攻击者，其次当前/最近；阶段 2 超 32 格返回 null |
| `updateBossSonic(PlayerEntity)` | 蓄力 34 tick、循环 160 tick，按阶段伤害开炮 |
| `updateMovementSpeed()` | 空闲 III；阶段 1 追逐 I；阶段 2+ 追逐 II；阶段 4 >64 格追逐 III |
| `updateBossFormRevert()` | 连续 600 tick 无目标退回普通形态 |
| `updatePhase4Trigger(float)` | 从 >30% 跌破 ≤30% 时触发 3 分钟增益，冷却 1200 tick |
| `meleeDamage()` / `meleePiercing()` / `spitDamage()` / `spitPiercing()` / `spitKnockback()` / `sonicDamage()` | 阶段数值档案 |
| `spitRangeSquared()` / `sonicMinRangeSquared()` / `sonicAttackMaxRangeSquared()` / `chaseStopRangeSquared()` | 阶段距离档案 |
| `resolveLockTarget` 取代者 `resolveBossTarget` | 阶段目标解析 |
| `isLockMode()` | **已移除**；口水穿甲改为发射时写入弹体 |

阶段 1 保留：`hearVibrationFrom` / `hearSupplement` / `triggerCuriosity` / `updateAngerBehavior` / `updateSonicMode` / 反击计数。
阶段 2+ 忽略声音。

#### 调试接口

私有常量 `DEBUG`（当前 `true`）控制日志，格式 `[Yao#<id>] <事件>`，事件含 `TRIGGER`、`SHOOT`、`SONIC enter/exit`、`BOSS form enter/exit`、`FORM`、`PHASE4 buff`、`MELEE`、`REGEN`。

### 3.3 `YaoYongshouSpitEntity`

`public class YaoYongshouSpitEntity extends LlamaSpitEntity`

| 成员 | 契约 |
| --- | --- |
| `DAMAGE_TYPE` | `hush:yao_spit` |
| `setAttack(float damage, float piercing, double knockback)` | 由发射方按阶段写入 |
| `onEntityHit` | 客户端返回；同类/发射者/非生物不结算；伤害走 `YaoArmorPiercingDamageSource`；击退按写入值 |
| 弹体归属 | 复用 `EntityType.LLAMA_SPIT`，用 `getOwner()` 判断来源 |

### 3.4 `YaoArmorPiercingDamageSource`

`public class YaoArmorPiercingDamageSource extends DamageSource`

| 成员 | 契约 |
| --- | --- |
| 构造函数 | `(RegistryEntry<DamageType>, Entity source, Entity attacker, float piercing)` |
| `getPiercing()` | 本发伤害中不参与护甲减伤的点数；由护甲 Mixin 读取 |

### 3.5 注册类

| 类 | 公开成员 | 说明 |
| --- | --- | --- |
| `ModEntities` | `YAO_YONGSHOU`、`register()` | 注册类型与默认属性 |
| `ModItems` | `YAO_YONGSHOU_SPAWN_EGG`、`register()` | 刷怪蛋 |
| `ModSounds` | 6 个 `SoundEvent`、`register()` | 音效事件 |

### 3.6 客户端

| 类 | 说明 |
| --- | --- |
| `HushClient` | 注册渲染器 |
| `YaoYongshouRenderer` | `MobEntityRenderer`，阴影 0.5，原版僵尸贴图，`EntityModelLayers.ZOMBIE` |
| `YaoYongshouModel` | `BipedEntityModel`；待机双臂前伸，命中时三段插值抡臂 |

### 3.7 Mixin

| Mixin 类 | 目标 | 方法 | 注入点 | 作用 |
| --- | --- | --- | --- | --- |
| `PlayerSoundMixin` | `PlayerEntity` | `playSound(Lnet/minecraft/sound/SoundEvent;FF)V` | `HEAD` | 采集玩家音效，只补没有 GameEvent 的声音 |
| `LivingEntityArmorMixin` | `LivingEntity` | `applyArmorToDamage(Lnet/minecraft/entity/damage/DamageSource;F)F` | `ModifyVariable(HEAD)` + `Inject(RETURN)` | 先把穿甲值从护甲计算中扣除，结算后加回 |

配置：

```json
{
  "required": true,
  "package": "com.example.hush.mixin",
  "compatibilityLevel": "JAVA_21",
  "mixins": ["PlayerSoundMixin", "LivingEntityArmorMixin"],
  "injectors": { "defaultRequire": 1 }
}
```

Loom 1.15 在 `remapJar` 阶段重映射方法名，**不需要 refmap**。

## 4. 资源与数据契约

### 4.1 `sounds.json`

位置 `assets/hush/sounds.json`，每个键是事件 ID 去掉命名空间后的 path：

```json
{
  "entity.yao_yongshou.spit": {
    "subtitle": "subtitles.hush.entity.yao_yongshou.spit",
    "sounds": [{ "name": "minecraft:entity.llama.spit", "type": "event" }]
  }
}
```

复用其他命名空间的事件必须写 `"type": "event"`，否则解析为空事件。

### 4.2 `damage_type`

`data/hush/damage_type/yao_spit.json` 与 `yao_melee.json` 字段相同：

```json
{ "message_id": "yao_spit", "exhaustion": 0.1, "scaling": "never" }
```

`scaling` 必须为 `never`，否则和平难度下伤害被清零。新增伤害类型时同步：RegistryKey → 数据文件 → `lang` 的 `death.attack.<message_id>`。

### 4.3 语言文件

`assets/hush/lang/zh_cn.json` / `en_us.json`，键成对出现：`entity.hush.yao_yongshou`、`item.hush.yao_yongshou_spawn_egg`、`subtitles.hush.entity.yao_yongshou.*`、`death.attack.yao_spit`、`death.attack.yao_melee`。

### 4.4 模型与贴图

`assets/hush/models/item/yao_yongshou_spawn_egg.json`：

- **不能**写 `"parent": "item/generated"`（会走 `builtin/generated`，忽略自定义 `elements`）。
- 无 `parent`，内联 `display` / `gui_light`，单个元素 `from [0,0,7.5] to [16,16,7.5]`，`north`/`south` 共面。

## 5. 稳定度与兼容性

| 级别 | 内容 | 变更规则 |
| --- | --- | --- |
| **稳定** | 实体/物品/伤害类型/音效事件 ID、字幕键、死亡信息键、入口类名、Mixin 配置文件名 | 改名或移除属于破坏性变更，必须升 minor 并在 CHANGELOG 标注 |
| **实验** | `onPlayerSound`、公开覆写方法时序、刷怪蛋模型、渲染器、阶段数值 | 可调整，需同步 SPEC/API |
| **内部** | 私有字段与常量、Goal 组合、日志文案、`DEBUG` | 可自由改动 |

- 仅支持 Minecraft 1.21.1；升级游戏版本需重新核对 `LlamaEntity`、`ProjectileAttackGoal`、`LivingEntity#applyArmorToDamage` 的签名。
- 不提供配置文件与网络包；行为由编译期常量决定。

## 6. 扩展指南

### 6.1 新增音效事件

1. `ModSounds` 增加 `register("entity.yao_yongshou.<name>")`。
2. `assets/hush/sounds.json` 增加条目，`sounds[].type` 写 `event`。
3. 两个 `lang` 文件增加字幕键。
4. 同步 SPEC §7。

### 6.2 调整阶段数值

常量与阶段档案都在 `YaoYongshouEntity` 顶部/阶段方法中，改完后：

1. 更新 [SPEC §6 数值总表](SPEC.md#6-数值总表)。
2. 按 [SPEC §10 验收用例](SPEC.md#10-验收用例) 回归。
3. 在 CHANGELOG 记录。

### 6.3 部分穿甲

只传 `YaoArmorPiercingDamageSource`，不要拆成两次 `damage()`：同一 tick 的第二次伤害会触发无敌帧而被吞掉。

## 7. 日志与排障

| 现象 | 排查点 |
| --- | --- |
| 字幕显示键名 | `sounds.json` 的 `subtitle` 与 `lang` 键不一致 |
| `Unable to play empty soundEvent` | 少了 `"type": "event"` |
| 启动报 `InvalidMixinException` | 目标方法签名变化或 `hush.mixins.json` 未更新 |
| 护甲穿透无效 | `LivingEntityArmorMixin` 是否注入；伤害源是否为 `YaoArmorPiercingDamageSource` |
| 和平难度无伤害 | 伤害类型是否 `scaling: never`；声波是否用了 `minecraft:sonic_boom` |
| 刷怪蛋有厚度/重影 | 模型是否误加 `item/generated` parent |
| 阶段不变化 | `updateFormState` 是否每 tick 执行；最大生命是否被羊驼随机化（`initAttributes` 必须空实现） |
