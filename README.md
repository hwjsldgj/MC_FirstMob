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
- **首发更快**：锁定目标的瞬间，只要看得见且在 20 格内就先吐一口，不等开火倒计时
- **冷却 1.7 秒**：吐口水间隔由羊驼默认的 40 tick 缩短到 34 tick（少 0.3 秒）
- **近战补位**：`CloseMeleeGoal`（优先级 4，仅 3 格内），只在远程不可用时触发
- **开火前面向目标**：身体与头部立即转向目标，再生成口水

### 听觉（主动攻击）

- 监听 **玩家发出的声音**：`PlayerEntity#playSound`（受伤、吃东西、喝药、游泳、落地、攻击……）＋ 挖掘/放方块动作（`PlayerInteractionMixin`）
- **脚步声算"声音"，但不算"持续吵闹"**：走 3 步就能触发，但触发后只走路不会一直维持仇恨
- 20 格内同一玩家 **5 秒**内声音 **≥3 次** → 主动锁定他，随后交给远程 AI 走近并吐口水
- 窗口内 **非脚步**声音 **<3 次** → 停止攻击；停火后 5 秒内纯脚步声不会再次触发
- **每次触发至少攻击一次**：锁定后 5 秒内必定补上一口，打不出去（被墙挡等）就作罢
- **25 格**之外放弃追击（无论目标是声音触发还是被激怒）
- 被姚永寿自己打出的受伤音效不计入（否则仇恨会自己续上）
- 挨打引发的仇恨不受"安静停火"约束（原版反击逻辑照常）

### 口水 `YaoYongshouSpitEntity`

- 命中造成 **4 点伤害 + 0.9 击退**（推离姚永寿的方向）
- 自定义伤害类型 `firstmob:yao_spit`（`scaling: never`），**和平难度下同样生效**
- 自定义弹道：速度 2.5、散布 0.4，并按重力做抛物线补偿，命中率高于原版羊驼
- **同类免伤**：同类之间不索敌（`setTarget` 过滤），口水命中同类也不结算伤害

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
│   ├── YaoYongshouEntity.java      # 羊驼行为、目标过滤、吐口水弹道、听觉索敌
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

## 许可

CC0-1.0，见 [LICENSE](LICENSE)。
