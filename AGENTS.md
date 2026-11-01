# AGENTS.md

给后续接手本项目的 AI / 协作者的工程约定。**改代码前先读这一页**；行为细节不在本文件重复，一律以 [docs/SPEC.md](docs/SPEC.md) 为准。

## 项目

Minecraft **1.21.1 Fabric** 模组，新增生物「姚永寿」（`firstmob:yao_yongshou`）与刷怪蛋。

- 外形用僵尸模型（`BipedEntityModel` + `EntityModelLayers.ZOMBIE` + 原版僵尸贴图），手臂姿势复用 `CrossbowPosing#meleeAttack`；不继承任何原版生物实体
- 行为 `extends LlamaEntity`，只沿用普通寻路 + 吐口水；商队、骑乘、繁殖、诱惑、跟随父母全部移除
- 没有幼年形态，不能繁殖 / 喂食 / 驯服
- 口水是自定义弹体 `YaoYongshouSpitEntity`，4 点伤害 + 击退，伤害类型 `firstmob:yao_spit`
- 刷怪蛋是自定义贴图的平面模型（无厚度、正反共面）

行为规则、状态机与数值：**[docs/SPEC.md](docs/SPEC.md)**；注册 ID、Java 接口、资源契约：**[docs/API.md](docs/API.md)**。

## 环境与命令

| 项目 | 值 |
| --- | --- |
| JDK | `D:\pcl\jdk-21`（`gradle.properties` 的 `org.gradle.java.home` 指向它） |
| Gradle 缓存 | `D:\pcl\gradle_cache`（`GRADLE_USER_HOME`） |
| Gradle | 9.4.0（Loom 1.15.5 的 `-remap` 插件要求 9.x） |
| 测试世界 | `run/saves/新的世界` |

```powershell
$env:JAVA_HOME='D:\pcl\jdk-21'
$env:GRADLE_USER_HOME='D:\pcl\gradle_cache'
.\gradlew.bat build     # 产物 build/libs/firstmob-1.0.0.jar
.\gradlew.bat runClient # 开发客户端，日志 run/logs/latest.log
```

国内镜像已写进仓库：阿里云 Maven（`build.gradle`）、BMCLAPI（`gradle.properties` 的 `loom_*`）、腾讯云 Gradle 发行包。

> 沙箱若拦截 gradle / git 对 `D:\pcl` 的写入，用 `danger-full-access` 重试一次；`write` / `edit` 工具不受影响。

## 代码结构

```
src/main/java/com/example/firstmob
├── Firstmob.java                   # 入口：注册实体/物品/音效、加入创造页签
├── entity
│   ├── YaoYongshouEntity.java      # 羊驼行为 + 听觉 + 口水 + 声波 + 反击
│   └── YaoYongshouSpitEntity.java  # 口水：伤害、击退、同类免伤
├── mixin
│   ├── PlayerSoundMixin.java       # 拦 PlayerEntity#playSound
│   └── PlayerInteractionMixin.java # 挖 / 交互方块也算"弄出动静"
└── registry
    ├── ModEntities.java            # 实体类型 + 属性
    ├── ModItems.java               # 刷怪蛋
    └── ModSounds.java              # 自定义音效事件（转发原版声音，换字幕）
src/client/java/com/example/firstmob/client
├── FirstmobClient.java             # 客户端入口（只注册渲染器）
├── YaoYongshouModel.java           # 僵尸骨架 + 僵尸手臂动画
└── YaoYongshouRenderer.java        # 僵尸模型渲染器
docs/                               # SPEC / API / 文档索引
```

## 不变量（改完逐条确认）

1. `targetSelector` 始终为空；反击只由 `damage()` 计数，不靠 `RevengeGoal`。
2. 同类永不被 `setTarget` 接受，口水永不伤害同类。
3. `sonicMode` 为 true 时 `shootAt` 永不生成口水：非 Boss（未锁定）的远程手段只由"5 秒内噪音是否 ≥ 9"决定，与距离无关。
4. 近战距离内不生成口水（普通 ≤3 格、锁定 ≤5 格）；锁定模式 5~16 格口水、17~40 格声波，蓄力开始后必须先把这一发打完。
5. 目标死亡当 tick 清空目标与攻击状态（残血锁定模式例外：改锁最近的存活玩家）。
6. 不存在幼年形态：`isBaby()` false、`getBreedingAge()` 0、`createChild()` null。
7. 音效事件必须用 `{"type": "event"}` 转发原版事件。
8. 刷怪蛋模型不能写 `"parent": "item/generated"`。
9. 口水与近战的伤害类型必须 `scaling: never`。
10. 反击计数只在 `shootAt` / `tryAttack` 命中时扣减。
11. Boss 形态退回只在「锁定模式已解除 + 连续 600 tick 无目标」时发生；退回后血量仍 <80% 时必须重新获得目标才再次变身（`bossFormDormant`），不能每 tick 反复进出。
12. 护甲/韧性每 tick 按形态与血量重算：普通 5/0、Boss 20/0、Boss 且 <20% 血 10/5。
13. 脱战回血只在玩家 200 tick 未造成伤害且未满血时挂生命恢复 I；满血或被再次打中立即移除，且只移除本机制挂的无限恢复 I。
14. 锁定模式声波蓄力开始后不许被近战/口水打断，直到开炮；普通噪音声波不受此限。

完整的判定条件、阈值与验收用例见 [docs/SPEC.md](docs/SPEC.md)。

## 代码规范

- 缩进沿用 Tab；阈值、距离、时长一律先定义成类顶部常量，注释说明用途，不写魔法数字。
- 注释写"为什么"（尤其是原版兼容、踩坑原因），不写"是什么"；公共方法用 Javadoc 说明契约。
- 日志统一走 `debug(...)`，受 `DEBUG` 开关控制；不要直接 `LOGGER.info`。
- Mixin 回调方法用 `firstmob$` 前缀；新增注入点要同步 `docs/API.md`。
- 资源 ID 一律用 `Firstmob.id(...)` 生成，不手写字符串拼接。
- 改 `setTarget` / `damage` / `shootAt` 这三个方法时，必须同步 [docs/SPEC.md](docs/SPEC.md) 的对应小节与不变量。

## 踩过的坑（别改回去）

1. **和平难度伤害**：`PlayerEntity.damage` 会把 `isScaledWithDifficulty()` 的伤害清零，所以口水和近战都用自定义伤害类型（`yao_spit` / `yao_melee`），`"scaling": "never"`；近战的挥击动画与尘柱按挥击触发，不靠 `damage()` 是否返回 true。
2. **击退方向**：`LivingEntity.takeKnockback(strength, x, z)` 内部会取反，要传「从目标指向攻击者」的向量。
3. **属性缺失**：羊驼默认没有 `GENERIC_ATTACK_DAMAGE`，近战会崩；`ModEntities` 里补了 `attack_damage=15`（铁傀儡数值）、`attack_knockback=0`，击退靠命中时向上顶 0.4。
4. **渲染取值**：模型层用 `context.getPart(EntityModelLayers.ZOMBIE)`（64×64 人形骨架），不是 `bakeLayer`；原版 `ZombieEntityModel` / `IronGolemEntityModel` 的泛型绑死在对应实体上，只能借骨架和 `CrossbowPosing`，不能直接实例化。
5. **空闲传送**：偏角随机在 35°~145°（左右随机），**不要出现 0°（正前方）**，否则会贴脸出现在玩家视野正中；半径 18~22 格随机，落点必须 3×3×4 空地（3×3 脚下实心 + 4 格空气）且区块已加载；玩家周围找不到就退到 48~64 格外，5 秒后重试。传送用 `requestTeleport` 并先停寻路。
6. **攻击动画**：别依赖 `handSwingProgress`（非玩家实体上很难确认是否发包）；命中时把 `ATTACK_START_AGE`（`TrackedData<Integer>`）写成 `this.age`，模型用渲染端 `age + tickDelta` 算进度——按帧插值才不卡顿，倒计时式的整数 tick 会一格一格跳。
7. **刷怪蛋模型**：不能写 `"parent": "item/generated"`——那会走 `builtin/generated`，按 alpha 生成 1/16 厚的挤压壳（154 个 quad），自定义 `elements` 被忽略，表现为重影/厚度。当前做法：无 parent，内联 `display`/`gui_light`，单个元素 `from [0,0,7.5] to [16,16,7.5]`，同时给 `north`/`south` 面，最终 2 个共面 quad。
8. **mixin 配置**：Loom 1.15 在 `remapJar` 阶段把注解里的方法名重映射成 intermediary，**不需要 refmap**；手写 `"refmap"` 反而会因文件不存在报错。
9. **近战抢目标**：`CloseMeleeGoal` 必须在 `canStart` 和 `shouldContinue` 里都卡距离；只卡 `canStart` 会让它一路追着目标跑、永远压住远程。
10. **刷怪蛋生成幼体**：两条路径都要堵。①在方块上使用：`PassiveEntity#initialize` 在 `SpawnReason.SPAWN_EGG` 下直接 `setBreedingAge(-24000)`（绕过 `setBaby`），所以要覆盖 `isBaby`/`getBreedingAge`/`setBreedingAge`；②对着实体使用：`MobEntity.interact` 是 final，会走 `interactWithItem` → `SpawnEggItem#spawnBaby` → `createChild`，而 `LlamaEntity#createChild` 造出的是原版小羊驼，必须覆盖成返回 null。只改 `interactMob` 没用。
11. **反击停不下来**：`LlamaEntity` 的 `targetSelector` 里有 `SpitRevengeGoal`（`RevengeGoal` 子类，优先级 1），目标被清空后会拿"上次攻击者"立刻重新锁定（日志表现为 `target X -> null` 紧跟 `target null -> X`）。必须 `targetSelector.clear(goal -> true)`，反击逻辑放 `damage()`。
12. **最大生命被羊驼随机化**：`LlamaEntity` 生成时经 `AbstractHorseEntity#initialize` 调 `AbstractDonkeyEntity#initAttributes`，把最大生命随机成 15~30，覆盖 `ModEntities` 的 100。必须把 `initAttributes(Random)` 覆盖成空实现，否则 80% / 60% / 20% 血量阈值全部按随机值算。

## 验证流程

1. `.\gradlew.bat build` 必须 `BUILD SUCCESSFUL`。
2. `.\gradlew.bat runClient` 起客户端；mixin 注入失败会在启动/进世界时报 `InvalidMixinException`，日志在 `run/logs/latest.log`。
3. 测试命令：

   ```mcfunction
   /kill @e[type=firstmob:yao_yongshou]
   /summon firstmob:yao_yongshou ~ ~ ~
   /give @s firstmob:yao_yongshou_spawn_egg
   ```

4. 按 [docs/SPEC.md 的验收用例](docs/SPEC.md#10-验收用例) 逐条回归，至少覆盖：走路不触发、挖 3 格触发、安静 5 秒停火、25 格放弃、9 次升声波、40 格解除、3 格内只近战、反击 3 次、刷怪蛋不生成幼体。

> 测试世界里常残留多只姚永寿（日志会出现多个 `[Yao#id]`），先 `/kill` 再测。

## 文档维护

- **行为 / 数值**：以 [docs/SPEC.md](docs/SPEC.md) 为唯一事实来源；先改 SPEC，再改代码，最后补 CHANGELOG。
- **接口 / 注册 ID / 资源键**：同步 [docs/API.md](docs/API.md)。
- **用户可见功能**：同步 `README.md` 的「玩法速览」。
- **每次改动**：在 `CHANGELOG.md` 对应版本下记录；文档目录与阅读顺序见 [docs/README.md](docs/README.md)。
- 正文用简体中文；ID、类名与 JSON 字段保持英文。

## 协作方式

- **回答精简**：先给结论与改了哪里，不铺垫、不复述需求；日志和代码只贴关键几行。
- **有歧义必须先问**：任何有歧义的需求（数值、触发条件、边界值、适用范围、对象）都要先提问确认，不要猜；宁可多问一句，也不要按错的理解改完再返工。
- **启动游戏窗口前先说明**：运行 `runClient` / 打开游戏窗口前，先用一句话说明是否需要用户协助，以及 **≤15 字**的启动目的（如「验证锁定距离与蓄力」）；无特殊情况不要直接启动。
- **及时提交**：一轮改动实现并验证通过后立即按下面的提交规范提交，不要等用户提醒「记得提交」。

## 提交规范

- 中文提交信息，前缀：`feat:` / `fix:` / `chore:` / `docs:`
- 提交前确认 `git status` 干净、`build` 通过
- 一次提交只做一件事；文档改动单独用 `docs:` 提交

## 已知限制

见 README 的「已知限制」与 [docs/SPEC.md §11](docs/SPEC.md#11-已知限制)，此处不再重复。
