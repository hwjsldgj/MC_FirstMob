# Firstmob · 姚永寿

一个 Minecraft **1.21.1 Fabric** 模组：新增生物「姚永寿」。
外形沿用村民的实体模型，行为则完全改用羊驼——羊驼式寻路、远程吐口水优先攻击，并附带自定义贴图的刷怪蛋。

## 环境

| 项目 | 版本 |
| --- | --- |
| Minecraft | 1.21.1 |
| Fabric Loader | 0.20.1 |
| Fabric API | 0.116.17+1.21.1 |
| Yarn 映射 | 1.21.1+build.3 |
| Fabric Loom | 1.15.5（`-remap`，需 Gradle 9.x） |
| Gradle | 9.4.0 |
| JDK | 21 |

## 功能

### 实体 `firstmob:yao_yongshou`

- **外形**：`VillagerResemblingModel` + 原版村民贴图，只借用模型，不继承村民实体
- **行为基类**：`LlamaEntity`，但只沿用**普通寻路 + 吐口水**：商队、绑定/骑乘、繁殖、诱惑、跟随父母等目标全部移除
- **属性**：沿用羊驼默认值，额外补 `attack_damage = 4`、`attack_knockback = 0.5`
  （羊驼默认没有攻击力，直接近战会因缺少属性而崩溃）
- **没有幼年形态**：`isBaby()` 恒为 false、年龄状态钉死为成年；不产生后代，拿刷怪蛋对它右键也不会生成小羊驼；不能繁殖、喂食或驯服

### 攻击

- **远程优先**：羊驼自带的 `ProjectileAttackGoal`（优先级 3），距离太远会自动走近，进入射程后停下吐口水
- **近距离只近战**：3 格内禁用一切远程（口水、声波都不放），只由 `CloseMeleeGoal`（优先级 2）负责还手；目标拉开到 3 格以外立刻切回远程
- **首发更快**：锁定目标的瞬间，只要看得见、在 20 格内且不在近战距离，就先吐一口，不等开火倒计时
- **冷却 1.7 秒**：吐口水间隔由羊驼默认的 40 tick 缩短到 34 tick（少 0.3 秒）
- **开火前面向目标**：身体与头部立即转向目标，再生成口水
- **两种远程互斥**：进入声波模式后只放声波，`shootAt` 直接返回，不会再吐口水
- **挨打反击最多 3 次**：每次被玩家打中，最多还手 3 次（口水/近战都算），打完就清目标收手；再打一次会重新计 3 次

### 听觉（主动攻击）

- 监听 **玩家发出的声音**：`PlayerEntity#playSound`（受伤、吃东西、喝药、游泳、落地、攻击……）＋ 挖掘/放方块动作（`PlayerInteractionMixin`）
- **分类计数**：走路、跳跃（落地）、受击 **各自独立**，每类在 5 秒窗口内只记 1 次（只记录，不作为攻击依据）；其它声音**发生即记 1 次**
- 20 格内同一玩家 **5 秒**内**真噪音**（吃、喝、挖、放、游泳等）**≥3 次** → 主动锁定他，随后交给远程 AI 走近并吐口水
- 只有真噪音能触发和维持仇恨；**光走路 / 跳跃 / 挨打都不会把它叫过来**，也停不下来之外不会无限续期
- **每次触发至少攻击一次**：锁定后 5 秒内必定补上一口，打不出去（被墙挡等）就作罢
- **25 格**之外放弃追击（无论目标是声音触发还是被激怒；声波模式为 40 格，见下）
- 挨打引发的仇恨不受"安静停火"约束（原版反击逻辑照常），但最多还手 3 次

### 声波（监守者）

- 20 格内同一玩家 **5 秒**内**真噪音**发声 **超过 8 次**（≥9 次）→ 升级为**声波模式**，优先级高于吐口水；走/跳/受击都不计入（否则走几步就会误触发）
- 与原版监守者一致：蓄力 34 tick → 胸口射出声波粒子束 + `entity.warden.sonic_boom`，造成 **10 点穿甲伤害**并沿射线方向击退；之后每 160 tick（8 秒）再来一次
- 目标**离开 40 格或死亡**才解除；期间不因"安静下来"停火，也不受 25 格放弃规则限制
- 解除后清掉该玩家的声音记录，需要重新攒够次数才能再次触发
- 与吐口水互斥：声波模式持续期间不会吐口水（反之吐口水阶段攒够 9 次声音会立即升级）；**3 格内同样不放声波**，只近战

### 口水 `YaoYongshouSpitEntity`

- 命中造成 **4 点伤害 + 0.9 击退**（推离姚永寿的方向）
- 自定义伤害类型 `firstmob:yao_spit`（`scaling: never`），**和平难度下同样生效**
- 自定义弹道：速度 2.5、散布 0.4，并按重力做抛物线补偿，命中率高于原版羊驼
- **同类免伤**：同类之间不索敌（`setTarget` 过滤），口水命中同类也不结算伤害

### 字幕与死亡信息

- 吐口水、蓄力、音波、环境音、受伤、死亡都换成 `firstmob:entity.yao_yongshou.*` 音效事件（声音文件复用原版羊驼/监守者），字幕统一显示「姚永寿：吼叫 / 受伤 / 死亡 / 吐口水 / 蓄力 / 音波尖啸」
- 口水击杀的死亡信息：`death.attack.yao_spit` →「XX 被 YY 的口水淹死了」

### 刷怪蛋 `firstmob:yao_yongshou_spawn_egg`

- 贴图：`assets/firstmob/textures/item/yao_yongshou_spawn_egg.png`（原图见 `素材/yao_yongshou.png`）
- 平面模型（无厚度、正反共面），位于创造模式「刷怪蛋」页签第二页

## 使用

```mcfunction
/summon firstmob:yao_yongshou ~ ~ ~
/give @s firstmob:yao_yongshou_spawn_egg
```

## 构建与运行

```powershell
# 需要 JDK 21
./gradlew build        # 产物：build/libs/firstmob-1.0.0.jar
./gradlew runClient    # 启动开发客户端
```

国内网络所需镜像已写进仓库：

- Maven：阿里云（`build.gradle`）
- Minecraft 资源 / 版本清单：BMCLAPI（`gradle.properties` 中的 `loom_*` 变量）
- Gradle 发行包：腾讯云（`gradle/wrapper/gradle-wrapper.properties`）

`gradle.properties` 里的 `org.gradle.java.home` 指向本机 JDK 21，换机器时请按需修改。

## 目录结构

```
src/main/java/com/example/firstmob
├── Firstmob.java                   # 入口：注册 + 创造页签
├── entity
│   ├── YaoYongshouEntity.java      # 羊驼行为、目标过滤、吐口水弹道、听觉索敌、声波
│   └── YaoYongshouSpitEntity.java  # 口水弹体：伤害 / 击退 / 同类免伤
├── mixin
│   ├── PlayerSoundMixin.java       # 监听玩家音效，转给听觉系统
│   └── PlayerInteractionMixin.java # 挖掘/放方块也算"弄出动静"
└── registry
    ├── ModEntities.java            # 实体类型 + 属性
    └── ModItems.java               # 刷怪蛋

src/main/resources
├── fabric.mod.json                 # 模组元数据
├── firstmob.mixins.json            # mixin 配置（玩家音效监听）
├── assets/firstmob/                # 贴图、模型、语言文件
└── data/firstmob/damage_type/      # 自定义伤害类型

src/client/java/com/example/firstmob/client
├── FirstmobClient.java             # 客户端入口
└── YaoYongshouRenderer.java        # 村民模型渲染器
```

## 已知限制

- 刷怪蛋是单面平面模型：侧视会变成一条线，掉落物转到背面时看不到图案
- 只保留村民「外观」，村民的交易、职业、脑 AI 等一概不继承
- 和平难度下伤害可生效，但创造模式玩家仍免疫伤害（只会被击退）
- 声波沿用原版 `minecraft:sonic_boom` 伤害类型（`scaling: always`）：和平难度伤害归零，创造模式免疫伤害时连击退也不会有，只剩粒子与音效

## 许可

CC0-1.0，见 [LICENSE](LICENSE)。
