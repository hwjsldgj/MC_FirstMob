# 原版依赖盘点

逐个登记本项目对 Minecraft 原版的依赖，给出「是否必要」的判断与处理决定。改动依赖前先改本表，再改代码。

- **必要**：去掉就得复制原版实现或改变现有行为，且没有更划算的替代。
- **可解除**：有独立替代方案，成本与风险可控，且能在不改变可见行为的前提下完成。
- **保留**：技术上可解除，但代价高于收益，或缺少离线验证手段，登记为已知依赖。

## 1. 结构型依赖（继承 / Mixin）

| # | 依赖点 | 位置 | 用途 | 判断 | 决定 |
| --- | --- | --- | --- | --- | --- |
| 1 | `LlamaEntity` 继承 | `YaoYongshouEntity` | 寻路、碰撞、骑乘/繁殖框架、羊驼属性表 | 可解除 | **已解除（阶段五）**：改为 `PathAwareEntity` + `RangedAttackMob`，属性表照抄羊驼；旧存档羊驼标签被忽略，冒烟有专项断言 |
| 2 | `Vibrations` + `VibrationListener` + `GameEventTags.WARDEN_CAN_LISTEN` | `YaoYongshouEntity` | 监听玩家 `GameEvent`，是索敌与愤怒的主通道 | 必要 | 保留。自己实现等价于抄写监守者整套监听器，收益为负 |
| 3 | `PlayerEntity#playSound` 注入 | `PlayerSoundMixin` | 补充原版未发 `GameEvent` 的音效（吃、喝、挖掘等） | 必要 | 保留。替代只有逐事件监听，覆盖面反而更窄 |
| 4 | `LivingEntity#applyArmorToDamage` 注入 | `LivingEntityArmorMixin` | 实现「部分穿甲」：N 点绕护甲，其余照常 | 必要 | 保留。原版没有部分穿甲 API；拆成多次伤害会破坏受伤事件与无敌帧 |
| 5 | `LlamaSpitEntity` 继承 | `YaoYongshouSpitEntity` | 口水弹道、重力、命中判定、渲染 | 可解除 | 本次**保留**：抄写需同时复制渲染器与碰撞盒，且无法离线验证；改写计划见 [ARCHITECTURE](ARCHITECTURE.md) 阶段六 |
| 6 | `EntityType.LLAMA_SPIT` 注册表项 | `YaoYongshouEntity#shootSpit` | 口水实体类型 | 可解除 | **本次解除**：注册自有 `hush:yao_spit`，渲染仍复用原版 `LlamaSpitEntityRenderer`，物理不变 |
| 7 | `StatusEffectInstance` / `StatusEffects` | 阶段增益、脱战回血、速度 | 增益与速度档位 | 必要 | 保留。原版效果体系是与玩家一致的唯一做法 |
| 8 | `ServerBossBar` | Boss 血条 | 血条广播、玩家进出追踪 | 必要 | 保留 |
| 9 | `ServerWorld#spawnParticles` / `ParticleTypes` | 粒子表现 | 变身、狂怒、拖尾、扬尘 | 必要 | 保留。仅表现，不参与逻辑 |
| 10 | `DamageSource` / `DamageSources#sonicBoom` | 近战、口水、声波 | 伤害结算与死亡消息 | 必要 | 保留 |

## 2. 资源与注册表依赖

| # | 依赖点 | 位置 | 判断 | 决定 |
| --- | --- | --- | --- | --- |
| 11 | `minecraft:entity.llama.*`、`minecraft:entity.warden.*` 声音文件 | `assets/hush/sounds.json` | 可解除，但需自有 ogg | 保留。仓库内没有音频资产，自制音效不在本次范围；字幕已是自有文本 |
| 12 | 僵尸贴图 `textures/entity/zombie/zombie.png`、`EntityModelLayers.ZOMBIE` | `YaoYongshouRenderer` / `YaoYongshouModel` | 设计选择 | 保留。「僵尸外形 + 羊驼行为」是模组设定 |
| 13 | 原版 AI Goal（`SwimGoal`、`MeleeAttackGoal`、`ProjectileAttackGoal`、`LookAtEntityGoal`、`LookAroundGoal`、`WanderAroundFarGoal`） | `entity` 包 | 必要 | 保留。已被 `initGoals` 精确排序，替换没有任何收益 |
| 14 | `BlockTags.OCCLUDES_VIBRATION_SIGNALS` | 听觉遮挡 | 必要 | 保留。与振动通道共用同一套遮挡规则 |
| 15 | `Items`/`ItemGroups`/`SpawnEggItem` | 刷怪蛋 | 必要 | 保留 |
| 16 | Fabric API（注册、默认属性、渲染器注册、物品栏事件） | 全局 | 必要 | 保留。已在 `fabric.mod.json` 声明最低版本 |

## 3. 本次解除的依赖

### 3.1 `EntityType.LLAMA_SPIT` → `hush:yao_spit`

- **原状**：`new YaoYongshouSpitEntity(EntityType.LLAMA_SPIT, world)`，口水在世界里登记为原版羊驼口水。
- **问题**：与真正的羊驼口水无法区分；`/kill @e[type=minecraft:llama_spit]`、其他模组的弹射物逻辑、统计与调试都会误伤；也无法给口水单独设定追踪范围。
- **方案**：注册自有实体类型，尺寸/追踪参数照抄原版羊驼口水（`0.25×0.25`、追踪 4 格、每 10 tick 同步）；客户端仍注册原版 `LlamaSpitEntityRenderer`，渲染与物理完全不变。
- **影响**：见 `BREAKING.md`。旧世界里不会有残留口水（弹射物存在时间极短）。

## 4. 仍然存在的升级风险

| 风险点 | 触发条件 | 现状缓解 |
| --- | --- | --- |
| `applyArmorToDamage` 签名变化 | Yarn 映射或原版重构 | 编译期直接失败，不会静默 |
| `playSound(Lnet/minecraft/sound/SoundEvent;FF)V` 描述符变化 | 同上 | 编译期失败（`@Inject` 找不到方法） |
| 羊驼行为调整（商队、繁殖、骑乘） | 版本升级 | 阶段五换基类后这些接口随羊驼一起消失；仍保留 `goalSelector` 清空，并让 `interactMob` 返回 `PASS` |
| `Vibrations` 接口调整 | 版本升级 | 仅 `YaoVibrationCallback` 一处实现 |
| 版本区间只写验证范围（`minecraft >=1.21.1 <1.21.2`），`fabric-api` 无上界 | 玩家装到 1.21.2+ 时会被拒载 | 已缓解：加载期直接拒绝，不会装上去才崩；`fabric-api` 仍靠下界 |
| 客户端资源复用原版（zombie 贴图、`EntityModelLayers.ZOMBIE`、llama/warden 音效事件） | 原版改名或改路径 | 未缓解：资源校验只查 JSON 语法与语言键，不查原版资源存在性；失效表现为粉黑格/无声 |
| `applyArmorToDamage` 全局注入 | 与其他修改护甲或包装 `DamageSource` 的模组共存 | 未缓解：无兼容性测试；叠加顺序与双重扣加只在运行期体现，不报错只算错 |

## 5. 维护规则

- 新增原版依赖（新基类、新 Mixin、新注册表项）必须在本文登记一行，写明用途与判断。
- 判定为「可解除」但本次不做的，必须写清阻塞原因（缺验证手段 / 成本 / 依赖其他阶段）。
- 每次真正解除依赖时，同步更新 `BREAKING.md` 与 `CHANGELOG.md`。
