# AGENTS.md

给后续接手本项目的 AI / 协作者的工程约定。**改代码前先读这一页**；行为细节不在本文件重复，一律以 [docs/SPEC.md](docs/SPEC.md) 为准。

## 项目

Minecraft **1.21.1 Fabric** 模组「肃静」（`hush`），新增生物「姚永寿」（`hush:yao_yongshou`）与刷怪蛋。

- 外形用僵尸模型 + 原版僵尸贴图；行为 `extends LlamaEntity`，只沿用普通寻路 + 吐口水
- 没有幼年形态，不能繁殖 / 喂食 / 驯服 / 骑乘；同类不索敌、口水不伤同类
- 按血量分四阶段：<80% 进 Boss，60% 起完全锁定，≤30% 狂暴
- 口水、近战、声波都按阶段给伤害；阶段 3/4 的部分伤害无视护甲
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
.\gradlew.bat build     # 产物 build/libs/hush-2.0.0.jar
.\gradlew.bat runClient # 开发客户端，日志 run/logs/latest.log
```

国内镜像已写进仓库：阿里云 Maven、BMCLAPI、腾讯云 Gradle 发行包。

> 沙箱若拦截 gradle / git 对 `D:\pcl` 的写入，用 `danger-full-access` 重试一次；`write` / `edit` 工具不受影响。

## 代码结构

```
src/main/java/com/example/hush
├── Hush.java                       # 入口：注册实体/物品/音效、加入创造页签
├── entity
│   ├── YaoYongshouEntity.java      # 羊驼行为 + 四阶段战斗 + 振动听觉 + 声波 + 反击
│   ├── YaoYongshouSpitEntity.java  # 口水：伤害 / 穿甲 / 击退 / 同类免伤
│   └── YaoArmorPiercingDamageSource.java  # 带穿甲值的伤害源
├── mixin
│   ├── PlayerSoundMixin.java       # 拦 PlayerEntity#playSound，只补未记录音效
│   └── LivingEntityArmorMixin.java # 把穿甲值从护甲结算中拆出来
└── registry
    ├── ModEntities.java            # 实体类型 + 属性
    ├── ModItems.java               # 刷怪蛋
    └── ModSounds.java              # 自定义音效事件（转发原版声音，换字幕）
src/client/java/com/example/hush/client
├── HushClient.java                 # 客户端入口（只注册渲染器）
├── YaoYongshouModel.java           # 僵尸骨架 + 僵尸手臂动画
└── YaoYongshouRenderer.java        # 僵尸模型渲染器
docs/                               # SPEC / API / 文档索引
```

## 不变量（改完逐条确认）

1. `targetSelector` 始终为空；反击只由 `damage()` 计数，不靠 `RevengeGoal`。
2. 同类永不被 `setTarget` 接受，口水永不伤害同类。
3. 阶段 1 的远程手段只由愤怒档位决定：警觉吐口水，100 激怒只在 17~24 格放声波；6~16 格仍吐口水。
4. 近战距离内不生成口水（所有阶段 ≤5 格）；声波蓄力开始后必须先打完这一发。
5. 阶段 2+ 完全不听声音；阶段 2 超 32 格脱锁，阶段 3/4 永不脱锁。
6. 阶段 4 的 17~24 格随机档：75% 口水 8 点、25% 声波 12 点，均带击退。
7. 不存在幼年形态：`isBaby()` false、`getBreedingAge()` 0、`createChild()` null。
8. 音效事件必须用 `{"type": "event"}` 转发原版事件。
9. 刷怪蛋模型不能写 `"parent": "item/generated"`。
10. 口水与近战的伤害类型必须 `scaling: never`；声波用 `minecraft:sonic_boom`。
11. 反击计数只在 `shootAt` / `tryAttack` 命中时扣减。
12. Boss 形态退回只在连续 600 tick 无目标时发生；退回后血量仍 <80% 时必须重新获得目标才再次变身（`bossFormDormant`）。
13. 护甲每 tick 按阶段重算：5/10/15/20，韧性恒 0；攻击力同步为阶段近战总伤害。
14. 脱战回血只在玩家 200 tick 未造成伤害且未满血时挂生命恢复 I；满血或被再次打中立即移除。
15. 听觉只有一个愤怒入口：振动是主来源，音效只补"同一动作没有 GameEvent"的声音（2 tick 配对）。
16. 愤怒每玩家独立 0~100、每 20 tick -1；`<30` 冷静、`30~99` 警觉、`100` 激怒；声波进入 100、退出 `<90`。
17. 好奇只在阶段 1 冷静且无目标时触发：看声源 40 tick、40 tick 冷却；目标/声波/Boss 形态出现立即停止。
18. `Vibrations` 必须在服务端每 tick 推 `Vibrations.Ticker.tick`，并在 `updateEventHandler` 把 `EntityGameEventHandler` 交给回调。
19. 部分穿甲只能用 `YaoArmorPiercingDamageSource` + `LivingEntityArmorMixin`；**不要**拆成两次 `damage()`（第二次会被无敌帧吞掉）。
20. 阶段 4 的额外增益由「跌破 30%」事件触发，冷却 1200 tick，可重复；不能每 tick 重刷。

完整的判定条件、阈值与验收用例见 [docs/SPEC.md](docs/SPEC.md)。

## 代码规范

- 缩进沿用 Tab；阈值、距离、时长一律先定义成类顶部常量，注释说明用途，不写魔法数字。
- 注释写"为什么"（尤其是原版兼容、踩坑原因），不写"是什么"；公共方法用 Javadoc 说明契约。
- 日志统一走 `debug(...)`，受 `DEBUG` 开关控制；不要直接 `LOGGER.info`。
- Mixin 回调方法用 `hush$` 前缀；新增注入点要同步 `docs/API.md`。
- 资源 ID 一律用 `Hush.id(...)` 生成，不手写字符串拼接。
- 改 `setTarget` / `damage` / `shootAt` / `tryAttack` 时，必须同步 [docs/SPEC.md](docs/SPEC.md)。

## 踩过的坑（别改回去）

1. **和平难度伤害**：`PlayerEntity.damage` 会把 `isScaledWithDifficulty()` 的伤害清零，所以口水和近战都用自定义伤害类型（`yao_spit` / `yao_melee`），`"scaling": "never"`。
2. **部分穿甲不能拆两次伤害**：同一 tick 第二次 `damage()` 会走无敌帧被吞。用 `LivingEntityArmorMixin` 在 `applyArmorToDamage` 里先减穿甲值、RETURN 时加回。
3. **击退方向**：`LivingEntity.takeKnockback(strength, x, z)` 内部会取反，要传「从目标指向攻击者」的向量。
4. **属性缺失**：羊驼默认没有 `GENERIC_ATTACK_DAMAGE`；`ModEntities` 里必须补（基值 5），近战才不会崩。
5. **渲染取值**：模型层用 `context.getPart(EntityModelLayers.ZOMBIE)`，不是 `bakeLayer`。
6. **空闲传送**：偏角随机在 35°~145°（左右随机，**不要出现正前方**），半径 18~22 格，落点必须 3×3×4 空地；找不到就退到 48~64 格外，5 秒后重试。
7. **攻击动画**：别依赖 `handSwingProgress`；命中时把 `ATTACK_START_AGE` 写成 `this.age`，渲染端按 `age + tickDelta` 插值。
8. **刷怪蛋模型**：不能写 `"parent": "item/generated"`，否则按 alpha 生成挤压壳、忽略 `elements`。
9. **mixin 配置**：Loom 1.15 在 `remapJar` 阶段重映射方法名，**不需要 refmap**；手写 `"refmap"` 反而报错。
10. **近战抢目标**：`CloseMeleeGoal` 必须在 `canStart` 和 `shouldContinue` 里都卡 5 格。
11. **刷怪蛋生成幼体**：`isBaby`/`getBreedingAge`/`setBreedingAge` 与 `createChild` 两条路径都要堵。
12. **反击停不下来**：`LlamaEntity` 的 `targetSelector` 里有 `SpitRevengeGoal`，必须 `targetSelector.clear(goal -> true)`。
13. **最大生命被羊驼随机化**：`initAttributes(Random)` 必须空实现，否则 80/60/30% 阈值全部按随机值算。
14. **振动事件不是同一 tick 到达**：按距离延迟；音效补充靠 `accepts` 同 tick 标记 + `undoSupplementIfAny` 回滚。
15. **阶段回退**：回血跨过阈值只降阶段与撤增益，不直接退出 Boss 形态；退出只走 600 tick 无目标。

## 验证流程

1. `.\gradlew.bat build` 必须 `BUILD SUCCESSFUL`。
2. `.\gradlew.bat runClient` 起客户端；mixin 注入失败会在启动/进世界时报 `InvalidMixinException`。
3. 测试命令：

   ```mcfunction
   /kill @e[type=hush:yao_yongshou]
   /summon hush:yao_yongshou ~ ~ ~
   /give @s hush:yao_yongshou_spawn_egg
   ```

4. 按 [docs/SPEC.md 的验收用例](docs/SPEC.md#10-验收用例) 逐条回归，至少覆盖：阶段 1 听觉、24/32 格脱锁、阶段 3/4 永不脱锁、各阶段伤害与穿甲、阶段 4 随机档与增益冷却、回血降档、300 秒退回、刷怪蛋、同类、和平难度、创造模式近战。

> 测试世界里常残留多只姚永寿（日志会出现多个 `[Yao#id]`），先 `/kill` 再测。

## 文档维护

- **行为 / 数值**：以 [docs/SPEC.md](docs/SPEC.md) 为唯一事实来源；先改 SPEC，再改代码，最后补 CHANGELOG。
- **接口 / 注册 ID / 资源键**：同步 [docs/API.md](docs/API.md)。
- **用户可见功能**：同步 `README.md` 的「玩法速览」。
- **每次改动**：在 `CHANGELOG.md` 对应版本下记录。
- 正文用简体中文；ID、类名与 JSON 字段保持英文。

## 协作方式

- **回答精简**：先给结论与改了哪里，不铺垫、不复述需求。
- **有歧义必须先问**：数值、触发条件、边界值、适用范围有歧义时先提问，不要猜。
- **启动游戏窗口前先说明**：运行 `runClient` 前用一句话说明是否需要协助，以及 **≤15 字**的启动目的。
- **及时提交**：一轮改动实现并验证通过后立即提交。

## 提交规范

- 中文提交信息，前缀：`feat:` / `fix:` / `chore:` / `docs:`
- 提交前确认 `git status` 干净、`build` 通过
- 一次提交只做一件事；文档改动单独用 `docs:` 提交

## 已知限制

见 README 的「已知限制」与 [docs/SPEC.md §11](docs/SPEC.md#11-已知限制)。
