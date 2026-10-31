# Firstmob · 姚永寿

Minecraft **1.21.1 Fabric** 模组：新增生物「姚永寿」。
它长着村民的样子，行为却来自羊驼——会寻路、会走近、会吐口水；听到玩家弄出动静会主动找上门，吵得太厉害还会放监守者声波。

## 快速开始

```mcfunction
/summon firstmob:yao_yongshou ~ ~ ~
/give @s firstmob:yao_yongshou_spawn_egg
```

| 项目 | 版本 |
| --- | --- |
| Minecraft | 1.21.1 |
| Fabric Loader | ≥ 0.20.1 |
| Fabric API | 0.116.17+1.21.1（开发环境） |
| Java | 21 |

## 玩法速览

| 机制 | 表现 |
| --- | --- |
| 外形 | 村民模型与贴图，但不继承村民的任何行为 |
| 血量 / 体型 | Boss 配置：**100 点**生命，模型与碰撞箱都是村民的 **2 倍**大 |
| Boss 血条 | 靠近时屏幕顶部显示「姚永寿」红色血条，随伤害下降，死亡立即收起 |
| 常驻 | 不会因为玩家跑远被自然清除，只能打死或用指令移除 |
| 掉落经验 | 玩家击杀掉落 **200 点**经验 |
| 听觉索敌 | 20 格内，玩家 5 秒内弄出 **3 次真噪音**（挖掘、放置、吃喝、游泳等）就会被锁定 |
| 走路/跳跃/受击 | 只记录、不触发，光跑不会把它叫过来 |
| 远程口水 | 4 点伤害 + 击退，和平难度同样生效；冷却约 1.7 秒 |
| 贴身近战 | 3 格内只近战、不放任何远程；退出去立刻恢复 |
| 挨打反击 | 每次被打最多还手 **3 次**，打完收手；再打一次重新计数 |
| 监守者声波 | 5 秒内真噪音 **≥ 9 次**升级：蓄力 34 tick，10 点穿甲伤害 + 击退，每 8 秒一发 |
| 声波停止 | 目标离开 40 格或死亡才停（被打断除外）；期间不因安静停火 |
| 放弃追击 | 普通模式目标跑出 25 格即放弃 |
| 同类 | 不互相索敌，口水也不伤害同类 |
| 幼年/繁殖 | 没有幼年形态，不能繁殖、喂食或驯服 |
| 字幕 | 环境音、受伤、死亡、吐口水、蓄力、声波都有「姚永寿：…」专属字幕 |

完整的判定条件、状态机与数值见 [行为规范](docs/SPEC.md)；接口与资源契约见 [接口文档](docs/API.md)。

## 构建

```powershell
$env:JAVA_HOME='D:\pcl\jdk-21'
$env:GRADLE_USER_HOME='D:\pcl\gradle_cache'
.\gradlew.bat build        # 产物 build/libs/firstmob-1.0.0.jar
.\gradlew.bat runClient    # 开发客户端，测试世界 run/saves/新的世界
```

国内网络所需镜像已写进仓库：阿里云 Maven（`build.gradle`）、BMCLAPI（`gradle.properties` 的 `loom_*`）、腾讯云 Gradle 发行包（`gradle/wrapper`）。
`gradle.properties` 里的 `org.gradle.java.home` 指向本机 JDK 21，换机器时按需修改。

安装：把 `build/libs/firstmob-1.0.0.jar` 放进 `mods/`，同时安装对应版本的 Fabric Loader 与 Fabric API。

## 文档

| 文档 | 内容 |
| --- | --- |
| [文档索引](docs/README.md) | 全部文档的阅读顺序与维护规则 |
| [行为规范](docs/SPEC.md) | 状态机、听觉判定、攻击规则、数值总表、验收用例 |
| [接口文档](docs/API.md) | 注册 ID、Java 成员、Mixin、资源契约、扩展指南 |
| [AGENTS](AGENTS.md) | 开发环境、代码结构、不变量、踩坑、验证流程 |
| [CHANGELOG](CHANGELOG.md) | 版本变更 |

## 目录结构

```
src/main/java/com/example/firstmob
├── Firstmob.java                   # 入口：注册实体 / 物品 / 音效、创造页签
├── entity
│   ├── YaoYongshouEntity.java      # 羊驼行为 + 听觉索敌 + 吐口水 + 声波
│   └── YaoYongshouSpitEntity.java  # 口水弹体：伤害 / 击退 / 同类免伤
├── mixin                           # 玩家音效、挖掘/交互方块 的采集点
└── registry                        # 实体类型、刷怪蛋、自定义音效事件

src/client/java/com/example/firstmob/client
└── YaoYongshouRenderer.java        # 村民模型渲染器

src/main/resources                  # 贴图、模型、语言、sounds.json、伤害类型
docs/                               # SPEC / API / 文档索引
```

## 已知限制

- 刷怪蛋是单面平面模型：侧视是一条线，掉落物转到背面看不到图案。
- 创造模式玩家免疫伤害：口水只击退；声波连击退也不结算，只剩粒子与音效。
- 声波沿用原版 `minecraft:sonic_boom`（`scaling: always`），和平难度伤害归零。
- 方块声音按「挖掘 / 交互动作」计数，不按真实音效事件；交互失败、开箱子等也会计一次。
- 只保留村民外观，交易、职业、脑 AI 一概不继承。

## 许可

CC0-1.0，见 [LICENSE](LICENSE)。
