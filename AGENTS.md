# AGENTS.md

给后续接手本项目的 AI / 协作者的注意事项。行为约定写在这里，改代码前先读一遍。

## 项目是什么

Minecraft **1.21.1 Fabric** 模组，新增生物「姚永寿」（`firstmob:yao_yongshou`）与对应刷怪蛋。

- **外形**：村民（`VillagerResemblingModel` + 原版村民贴图），只是借模型，不继承村民实体
- **行为**：`extends LlamaEntity`（羊驼），但**只沿用普通寻路 + 吐口水**；商队、绑定/骑乘、繁殖、诱惑、跟随父母等目标全部移除
- **没有幼年形态**：`isBaby()` 恒 false、`setBaby()` 空实现；不能繁殖、不能喂食/驯服
- **口水**：自定义弹体 `YaoYongshouSpitEntity`，4 点伤害 + 击退，自定义伤害类型 `firstmob:yao_spit`
- **刷怪蛋**：自定义贴图的平面模型（无厚度、正反共面）

## 环境与命令

| 项目 | 值 |
| --- | --- |
| JDK | `D:\pcl\jdk-21`（`gradle.properties` 的 `org.gradle.java.home` 指向它） |
| Gradle 缓存 | `D:\pcl\gradle_cache`（`GRADLE_USER_HOME`） |
| Gradle | 9.4.0（Loom 1.15.5 的 `-remap` 插件要求 9.x） |

```powershell
$env:JAVA_HOME='D:\pcl\jdk-21'
$env:GRADLE_USER_HOME='D:\pcl\gradle_cache'
.\gradlew.bat build     # 产物 build/libs/firstmob-1.0.0.jar
.\gradlew.bat runClient # 开发客户端，测试世界 run/saves/新的世界
```

国内网络镜像已写进仓库：阿里云 Maven（`build.gradle`）、BMCLAPI（`gradle.properties` 的 `loom_*`）、腾讯云 Gradle 发行包。

> DSH 沙箱下 `D:\pcl` 属于受限写入区，跑 gradle / git / 改 `run/` 需要 `danger-full-access`；`write`/`edit` 工具不受影响。

## 代码结构

```
src/main/java/com/example/firstmob
├── Firstmob.java                   # 入口：注册实体/物品、加入创造页签、日志
├── entity
│   ├── YaoYongshouEntity.java      # 羊驼行为 + 听觉索敌 + 吐口水弹道
│   └── YaoYongshouSpitEntity.java  # 口水：伤害、击退、同类免伤
├── mixin
│   ├── PlayerSoundMixin.java       # 拦 PlayerEntity#playSound，收集玩家音效
│   └── PlayerInteractionMixin.java # 挖/放方块也算"弄出动静"
└── registry
    ├── ModEntities.java            # 实体类型 + 属性
    └── ModItems.java               # 刷怪蛋
src/client/java/com/example/firstmob/client
├── FirstmobClient.java             # 客户端入口（只注册渲染器）
└── YaoYongshouRenderer.java        # 村民模型渲染器
```

## 行为规则（当前版本，改动需同步 README/CHANGELOG）

### 听觉索敌

- 半径 **20 格**，统计窗口 **5 秒**（100 tick），所有阈值统一为 **3 次**
- **触发**：窗口内声音 ≥3 次 → `setTarget(玩家)`
- **维持**：窗口内「非脚步」声音 ≥3 次；否则停火（清目标）
- **每次触发至少攻击一次**：锁定后 5 秒（`GUARANTEE_TIMEOUT_TICKS`）内必须补一枪，超时则作废
- **停火冷却**：停火后 5 秒内，纯脚步声不会再触发（避免走两步就重新开打）
- **放弃追击**：目标超过 **25 格**立即清目标
- 不计数的声音：脚步声（不计入"维持"，但计入"触发"）、玩家死亡音效、**由姚永寿自己造成的受伤音效**（否则仇恨会自我续期）
- 挖掘 / 放方块通过 `ServerPlayerInteractionManager` 的 mixin 记为一次"巨响"（`loud = true`）

### 攻击

- 远程：`ProjectileAttackGoal`，优先级 3，射程 20 格，**冷却 34 tick**（羊驼默认 40，减 0.3 秒）
- 首发：`setTarget` 里若目标可见且在 20 格内，**立即** `shootAt` 一次，不等开火倒计时
- 近战：`CloseMeleeGoal`，优先级 4，仅 3 格内补位
- 开火前把 `yaw`/`bodyYaw`/`headYaw`/`pitch` 全部转向目标
- 同类不互殴：`setTarget` 过滤 `YaoYongshouEntity`，口水命中同类也不结算伤害

## 踩过的坑（别改回去）

1. **和平难度伤害**：`PlayerEntity.damage` 会把 `isScaledWithDifficulty()` 的伤害清零，所以口水用自定义伤害类型 `data/firstmob/damage_type/yao_spit.json`，`"scaling": "never"`。
2. **击退方向**：`LivingEntity.takeKnockback(strength, x, z)` 内部会取反，要传「从目标指向攻击者」的向量。
3. **属性缺失**：羊驼默认没有 `GENERIC_ATTACK_DAMAGE`，近战会崩；`ModEntities` 里补了 `attack_damage=4`、`attack_knockback=0.5`。
4. **渲染取值**：模型层用 `context.getPart(EntityModelLayers.VILLAGER)`，不是 `bakeLayer`。
5. **刷怪蛋模型**：**不能**写 `"parent": "item/generated"`——那会走 `builtin/generated`，按 alpha 生成 1/16 厚的挤压壳（154 个 quad），自定义 `elements` 被忽略，表现为重影/厚度。当前做法：无 parent，内联 `display`/`gui_light`，单个元素 `from [0,0,7.5] to [16,16,7.5]`，同时给 `north`/`south` 面，最终 **2 个共面 quad**。
6. **mixin 配置**：`firstmob.mixins.json` 被 `fabric.mod.json` 的 `mixins` 引用。Loom 1.15 会在 `remapJar` 阶段直接把注解里的方法名重映射成 intermediary（jar 里能看到 `method_5783`），**不需要 refmap**；手写 `"refmap"` 反而会因文件不存在报错。
7. **目标被抢**：近战若放优先级 2 会压住远程；必须 ≥4。
8. **刷怪蛋生成幼体**：两条路径都要堵。①在方块上使用：`PassiveEntity#initialize` 在 `SpawnReason.SPAWN_EGG` 下直接 `setBreedingAge(-24000)`（绕过 `setBaby`），所以要覆盖 `isBaby`/`getBreedingAge`/`setBreedingAge`；②对着实体使用：`MobEntity.interact` 是 final，会走 `interactWithItem` → `SpawnEggItem#spawnBaby` → `createChild`，而 `LlamaEntity#createChild` 造出的是**原版小羊驼**，必须覆盖 `createChild` 返回 null。只改 `interactMob` 没用。

## 验证流程

1. `.\gradlew.bat build` 必须 `BUILD SUCCESSFUL`
2. `.\gradlew.bat runClient` 起客户端，日志在 `run/logs/latest.log`；mixin 注入失败会在启动/进世界时报 `InvalidMixinException`
3. 测试命令：
   ```mcfunction
   /summon firstmob:yao_yongshou ~ ~ ~
   /give @s firstmob:yao_yongshou_spawn_egg
   ```
4. 听觉验证：在 20 格内走路（3 步触发）、吃东西、挖方块；安静 5 秒应停火；跑出 25 格应放弃

## 提交规范

- 中文提交信息，前缀：`feat:` / `fix:` / `chore:` / `docs:`
- 每次改动同步 `README.md`（功能说明）与 `CHANGELOG.md`
- 提交前确认 `git status` 干净、`build` 通过

## 已知限制

- 刷怪蛋是单面平面模型：侧视是一条线，掉落物转到背面看不到图案
- 创造模式玩家免疫伤害，只会被击退
- 方块音效按「挖/放动作」计数，不是按真实音效事件；播放声音/开箱子以外的方块交互也会算一次
- 只保留村民外观，交易、职业、脑 AI 一概不继承
