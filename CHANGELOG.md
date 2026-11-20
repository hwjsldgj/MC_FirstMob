# 更新日志

## 未发布

### 工程

- 构建不再写死 JDK 路径：`gradle.properties` 移除 `org.gradle.java.home`，本地与 CI 统一用 `JAVA_HOME` 选择 JDK 21
- 阶段数值集中到 `com.example.hush.combat.CombatProfile`，实体与 `ModEntities` 改为查表；新增 JUnit 5 单测（`CombatProfileTest`，9 条）
- 诊断日志默认关闭，排查时用 `-Dhush.debug=true` 打开
- `fabric.mod.json` 的 Fabric API 依赖补上最低版本 `>=0.116.17`
- 忽略 `desktop.ini`，移除历史 `javap` 转储 `se.txt`
- 新增 GitHub Actions 工作流（`.github/workflows/build.yml`）：PR 与 `main` 推送都跑 `build`（含单测）并上传 jar；合并 `main` 一律走 PR
- `main` 规则集新增必需状态检查 `build`（`strict=false`）：PR 的 CI 未通过时无法合并
- 实体职责拆分：AI 目标移到 `entity/goal`（6 个文件）、愤怒与听觉计数移到 `hearing/AngerLedger`、粒子移到 `visual/EntityParticles`；`YaoYongshouEntity` 由 1990 行降到 1751 行，行为不变
- 口水改用自有实体类型 `hush:yao_spit`（原复用 `minecraft:llama_spit`），渲染仍用原版 `LlamaSpitEntityRenderer`；影响与迁移见 `docs/BREAKING.md`
- 新增 `AngerLedgerTest`（12 例）：愤怒上限、衰减间隔、配对窗口、限频、离线清理；单测总数 21
- 修复：离线玩家的愤怒与听觉时间戳不再在长跑服务器上无限增长（每 20 tick 衰减时清理）
- CI 增加资源校验（JSON 语法 + `zh_cn`/`en_us` 键成对）、`timeout-minutes`、失败时上传测试报告
- CI 新增 `smoke` job：无头 `runServer` + `ci/smoke-datapack` 启动后 summon 实体，断言 Mixin 与实体注册在加载期无错；日志作为 artifact 上传
- `smoke` 优化：`HUSH_SMOKE_DONE` 出现后由 `ci/rcon_stop.py` 经 RCON 优雅停服（3m19s → 1m07s），超时降为 150 秒兜底；失败时输出日志尾部；仅改 `docs/**` 或 `*.md` 的 PR 自动跳过冒烟
- 构建配置减法：移除无发布目标的 `maven-publish` 插件与空 `publishing` 块；Java 版本只由 `options.release = 21` 一处声明
- 代码清理：删除 `YaoYongshouEntity` 两处悬空 Javadoc（无声明可挂）；`ModEntities` 私有构造器移到类末尾，注册 ID 统一走 `Hush.id(...)`
- `.gitattributes` 为 `*.py` 固定 LF：避免 Windows 检出后 shebang 变成 `bad interpreter`
- CI：全部 action 按 commit SHA 固定，`runs-on` 改用 `ubuntu-24.04`，新增 `.github/dependabot.yml` 每周升级 action
- 文档：`ARCHITECTURE` 不再写死实体行数（绝对值会随改动失效）
- 开启 Gradle 构建缓存（`org.gradle.caching=true`）：本地重复构建直接复用，CI 的 `smoke` job 可从 `setup-gradle` 缓存命中编译产物
- CI 把三个 Loom 端点覆盖参数抽成 workflow 级 `LOOM_ARGS`，`build` 与 `smoke` 共用；`hush-jar` artifact 排除 `-sources.jar`

### 文档

- SPEC：修正阶段 1「停在 8 格」的描述（实际由 `ProjectileAttackGoal` 决定，8 格只在阶段 2~4 生效）
- API：删除已移除的 `resolveLockTarget` / `isLockMode` 条目，更新 `DEBUG` 说明
- 清理实体类里过期的注释（愤怒上限、衰减间隔、传送距离、经验值、商队）
- AGENTS 新增「分支与合并（GitHub）」：记录 `main` ruleset 的禁止删除 / 禁强推 / 必须 PR、0 必需批准、必须解决 review 线程、无 bypass 角色、合并后自动删源分支，以及 `gh pr create` → CI → `gh pr merge` 的标准流程
- 记录 ruleset 曾开启 `require_last_push_approval` 导致作者无法合并、关闭后恢复 `CLEAN` 的实测结论
- README 与 AGENTS 的目录结构补充 `combat/CombatProfile.java`、`src/test` 与 `.github/workflows`
- API 修正 `combatPhaseFor` 的分档描述，并补上 `CombatProfile` 条目
- 新增 `docs/COUPLING.md`（原版依赖逐项盘点与必要性判断）、`docs/ARCHITECTURE.md`（包职责与拆分状态）、`docs/BREAKING.md`（破坏性变更与迁移）；`docs/README.md` 索引与 `AGENTS.md` 结构树同步

## 四阶段重做（开发阶段记录）

### 破坏性变更

- 模组改名为「肃静」，Mod ID 由 `firstmob` 改为 `hush`；Java 包改为 `com.example.hush`，入口类改为 `Hush` / `HushClient`，资源命名空间改为 `assets/hush`、`data/hush`
- 注册 ID 变为 `hush:yao_yongshou`、`hush:yao_yongshou_spawn_egg`、`hush:entity.yao_yongshou.*`、`hush:yao_spit`、`hush:yao_melee`；旧存档中的 `firstmob:` 实体不再注册，需要重新召唤
- 版本号不再写成发布号，构建物统一为 `0.1.0-SNAPSHOT`（`build/libs/hush-0.1.0-SNAPSHOT.jar`）

### 新增

- 四阶段战斗：血量 ≥80% 普通、<80% Boss、<60% 完全锁定、≤30% 狂暴；阶段按当前血量动态取档
- 阶段数值：护甲 5/10/15/20；近战 5/8/8/10（阶段 3/4 分别 4/5 点穿甲）；口水 3/4/5/6（阶段 3/4 分别 3/4 点穿甲）；声波 6/8/10/14
- 距离分段：阶段 1 口水 6~24、声波 17~24；阶段 2 口水 6~16、声波 17~32；阶段 3 口水 6~16、声波 17~48；阶段 4 口水 6~16、17~24 随机档、声波 25~64
- 追击停距 8/16/16/32 格；阶段 2 超 32 格脱锁，阶段 3/4 永不脱锁；阶段 4 >64 格只追且速度再 +1
- 阶段 4 的 17~24 格随机档：75% 口水 8 点 / 25% 声波 12 点，均带击退
- 阶段 4 额外增益：跌破 30% 时获得抗性 I、生命恢复 II、速度 I、力量 I、伤害吸收 I（各 3 分钟），冷却 1 分钟，可重复触发
- 部分穿甲实现：新增 `YaoArmorPiercingDamageSource` 与 `LivingEntityArmorMixin`，在 `applyArmorToDamage` 中把穿甲值排除在护甲减伤之外，其余伤害照常结算
- 口水与近战击退分档：阶段 1 无、阶段 2 击退 I、阶段 3/4 击退 II

### 调整

- 阶段 1 保留振动听觉与愤怒：30 警觉、100 激怒；激怒后只在 17~24 格放声波，6~16 格仍吐口水；脱锁距离 24 格
- 阶段 2+ 完全不听声音，硬锁当前/最近玩家；阶段 3/4 优先上次攻击者
- Boss 形态退回沿用旧机制：连续 600 tick 无目标退回普通形态，回血 ≥80% 清除 dormant
- 速度：空闲 III；阶段 1 追逐 I；阶段 2+ 追逐 II；阶段 4 >64 格追逐 III
- Boss 血条：阶段 4 紫色并加「（狂暴）」，其余阶段红色

### 移除

- 移除 <20% 附魔金苹果效果、低血护甲 10/5、低血攻击冷却缩短 20%
- 移除 `firstmob:yao_spit_piercing` 伤害类型与 `minecraft:bypasses_armor` 追加标签（改为部分穿甲）
- 移除旧的残血锁定进入/解除条件（满血 + 30 秒无伤），改由阶段阈值接管

## 未发布

> 以下为四阶段重做之前的未发布改动，其中部分数值已被现行四阶段规则取代。

### 修复

- 修复最大生命被羊驼随机化：`LlamaEntity` 生成时会把最大生命随机成 15~30，覆盖配置的 100，导致 80% / 60% / 20% 血量阈值全部失真；覆盖 `initAttributes(Random)` 为空实现后固定 100 血
- 和平难度下近战不生效：近战伤害改用自定义伤害类型 `firstmob:yao_melee`（`scaling: never`，护甲/抗性/盾牌照常结算），和平难度下仍造成 15 点伤害；同时挥击动画与 `dust_pillar` 改为按「挥击」触发，不再依赖伤害是否结算成功（创造模式等免疫目标也能看到近战动作）
- 新增近战死亡信息 `death.attack.yao_melee`：「%1$s 被 %2$s 一巴掌拍死了」
- 声波距离统一：非 Boss（激怒）与锁定模式都只有 **20~40 格**才开炮，20 格内改回口水/近战；40 格以外只追不打（此前普通模式 3~40 格都会放声波，锁定模式还没有 40 格上限）
- 修复普通模式口水被吞：`shootAt` 不再做全局限流，切目标的首发一枪必定打出；锁定模式用独立的 `nextSpitTick` 计时，不再与 `ProjectileAttackGoal` 的 34 tick 冷却叠加成约 68 tick 的空窗
- 非 Boss 远程改由愤怒档位决定：警觉（约 3 次真噪音）吐口水，激怒（约 9 次）声波替代口水；不再用固定的 9 次窗口，也不再和距离绑定
- README 的攻击距离速查按 **Boss / 非 Boss** 分列，避免两套规则混读

### 新增

- 听觉改为监守者式振动 + 每玩家愤怒：振动为主，音效只补原版不记录的声音（2 tick 配对去重）；大噪音 +12、脚步/落地/受击 +5（每类 5 秒限频）、每 20 tick -1；30 警觉吐口水、100 激怒放声波；冷静时好奇看向声源 40 tick；锁定后狂暴只改血条表现
- Boss 阶段不再接受声音，完全锁定当前/最近玩家：≤5 近战、5~16 口水 4 点、17~32 站定声波、32~40 边追边声波、40~48 只追（速度 II）、>48 放弃
- Boss 血量 <60% 时口水改为 **5 点穿甲**（新增 `firstmob:yao_spit_piercing` 并登记到 `minecraft:bypasses_armor`）
- 濒死伤害吸收由 III 下调为 II
- 粒子效果（全部为 `minecraft:` 原版粒子，服务端生成）：
  - 血量 < 80% 转 Boss 形态时释放一圈 `dust_color_transition` 尘团（熔岩橙 → 暗紫，80 颗）
  - 血量 < 20% 结算濒死效果时释放 `totem_of_undying` 粒子（100 颗，回血后再次下穿阈值会再触发）
  - 持有任意速度（I / II / III）且在地面走路时脚下拖 `firework` 粒子（每 4 tick 2 颗）
  - 近战命中时在目标脚下升起 `dust_pillar` 尘柱（6 颗，贴图取脚下方块，空气/流体退回石头）
- Boss 形态常驻增益：进入 Boss 形态始终获得**抗性提升 I**（无限时长），退回普通形态时移除
- 护甲分档：普通形态 5 点护甲 / 0 韧性；Boss 形态 20 / 0；Boss 且血量 <20% 时改为 **10 护甲 + 5 韧性**，回到 20% 以上立刻改回 20/0
- Boss 形态可退回：解除锁定后连续 **30 秒没有目标**，退回普通形态（体型 1 倍、护甲 5、力量与抗性提升消失、血条收起）；血量仍 <80% 时保持普通形态，需重新获得目标才再次变身
- 锁定模式距离重做：5~16 格口水、17~32 格站定声波、超过 32 格到 40 格边追边声波、>40 格只追不打；声波蓄力一旦开始就先打完这一发（期间不切近战/口水）
- 空闲游走：与玩家保持 **≥16 格**站距（<16 格后退、>32 格靠近、**16~32 格站定只看人**），**>48 格**传送到侧前方随机 18~22 格；落点要求 **3×3×4** 空地，玩家周围没有合适落点就退到 48~64 格外、每 5 秒重试
- 移动速度分档：游走速度 III、战斗追击速度 II、常态 Boss 形态速度 I
- 追玩家时全程挂速度 II（锁定与普通追击都一样），进入近战距离自动收回，退回 Boss 形态的永久速度 I
- 外形改为僵尸：`BipedEntityModel` + `EntityModelLayers.ZOMBIE` + 原版僵尸贴图，待机双臂前伸；近战命中播放 10 tick 的抬臂抡下动画（`ATTACK_START_AGE` 数据同步，按渲染端 age 插值）
  - 原版 `CrossbowPosing#meleeAttack` 的微幅摆动在 2 倍体型下几乎看不清，所以攻击段改成三段插值的自定义抡臂
  - 动画进度改用「命中时的 age + 渲染端 tickDelta」计算，逐帧插值，不再按整数 tick 跳变（修复卡顿与延迟）
  - 铁傀儡模型泛型绑死在 `IronGolemEntity`，无法直接复用；抡臂动作参考了它的双臂砸地
- 近战数值对齐铁傀儡：伤害 15（原 4）、`attack_knockback` 0（原 0.5），命中额外向上顶 `0.4 × (1 - 击退抗性)`
- 姚永寿改为分阶段 Boss：
  - 生成时为普通形态：100 血、护甲 0、体型 1 倍、不显示血条
  - 血量掉到 80% 以下转 Boss 形态（单向）：体型 2 倍、护甲 20、屏幕顶部红色 Boss 血条，并获得永久速度 I、力量 I
  - 血量低于 20% 时获得附魔金苹果效果：伤害吸收 II、生命恢复 II（30 秒）、抗性提升与抗火（各 5 分钟）；每次从 20% 以上掉下来触发一次
  - 血量低于 60% 进入残血锁定：死咬玩家不放，20 格内吐口水、20 格外放声波；优先咬最近打过它的玩家，被打也不换目标；目标死亡改锁最近的存活玩家
  - 锁定解除条件：自己满血且 30 秒内没有玩家造成伤害（两条同时满足）
  - 锁定近战距离放宽到 5 格（普通模式仍是 3 格）：5 格内只近战、不吐口水
  - 锁定追击加速：距离玩家 >40 格获得速度 II，回到 20 格内退回 Boss 形态的永久速度 I
  - 血量低于 20% 时攻击冷却缩短 20%：口水 34→27 tick、声波蓄力 34→27、循环 160→128
  - `BossForm` / `LowHealthTriggered` 写入 NBT，读档不会退回普通形态、濒死效果也不会立刻重刷
  - 击杀掉落 500 点经验，`setPersistent()` 常驻不被自然清除

### 文档

- 新增 `docs/SPEC.md`：行为规范（状态机、听觉判定、攻击与声波、数值总表、验收用例、已知限制）
- 新增 `docs/API.md`：接口文档（注册 ID、Java 成员与契约、Mixin 注入点、资源与数据契约、稳定度分级、扩展指南）
- 新增 `docs/README.md`：文档索引、阅读顺序与维护规则（SPEC 作为行为唯一事实来源）
- 重写 `README.md`：面向玩家的玩法速览 + 文档导航，实现细节移交 SPEC / API
- `README.md` 增加「支持的版本」表（与 `fabric.mod.json` 的 `depends` 同步：Minecraft `~1.21.1`、Fabric Loader ≥ 0.20.1、Java ≥ 21、双端安装），玩法速览按基础 / 血量阶段 / 战斗 / 攻击距离速查 / 其他重排并补齐机制
- 精简 `AGENTS.md`：删除与 SPEC 重复的行为细节，保留环境、结构、不变量、踩坑、验证与提交规范

## 初始开发版（开发阶段记录）

### 新增

- 生物「姚永寿」（`firstmob:yao_yongshou`）及其刷怪蛋
- 刷怪蛋使用自定义贴图（平面模型）

### 调整

- 实体由「完全套用村民」改为「村民外形 + 羊驼行为」
  - 只沿用羊驼的普通寻路与吐口水；商队、绑定/骑乘、繁殖、诱惑、跟随父母等目标已移除
  - 远程吐口水优先，距离太远会自动接近；3 格内近战补位
  - 开火前身体与头部转向目标
  - 同类之间不索敌、不互伤
  - 没有幼年形态：刷怪蛋不再生成小羊驼，也不能繁殖/喂食/驯服
  - 对着姚永寿使用刷怪蛋不再生出小羊驼（`createChild` 返回 null，不产生后代）
- 口水改为自定义弹体：4 点伤害 + 击退，和平难度同样生效；提高速度并降低散布
- 吐口水冷却 40 → 34 tick（缩短 0.3 秒）；锁定目标瞬间先补一枪
- 补充 `attack_damage` / `attack_knockback` 属性，修复近战崩溃
- 新增听觉索敌：20 格内玩家 5 秒发声 ≥3 次即主动吐口水攻击，非脚步声 <3 次停火，每次触发至少攻击一次
  - 脚步声只用于触发、不维持仇恨；停火后 5 秒内不会被脚步直接叫回去
  - 挖掘/放方块同样算声音；被姚永寿打出的受伤音效不计
  - 目标跑出 25 格即放弃追击
- 新增监守者声波：20 格内玩家 5 秒**非脚步**发声超过 8 次（≥9）时升级为声波模式——蓄力 34 tick 后开火，10 点穿甲伤害 + 击退，每 160 tick（8 秒）一次；目标离开 40 格或死亡才停；与吐口水互斥，但近战照常
- 修复声波误触发：走路 / 跳跃（落地）/ 受击三类各自独立，5 秒窗口内每类只记 1 次，其它声音发生即记 1 次；三类声音只记录，**不再触发攻击、也不维持仇恨**（只有真实动作声能触发）
- 近距离（3 格内）只准近战：口水与声波都被禁用，目标拉开距离后才切回远程
- 挨打反击设有上限：每次被玩家打中最多还手 3 次（口水/近战都算），打完即收手；声音触发与声波模式不受此限制
- 新增自定义音效事件（`firstmob:entity.yao_yongshou.*`，转发原版羊驼/监守者事件）：环境音、受伤、死亡、吐口水、蓄力、声波的字幕统一为「姚永寿：吼叫 / 受伤 / 死亡 / 吐口水 / 蓄力 / 音波尖啸」
- 口水击杀的死亡信息改为「XX 被 YY 的口水淹死了」（`death.attack.yao_spit`）
- 修复反击永远停不下来：清空羊驼原版 `targetSelector`（`SpitRevengeGoal` 会在清空目标后立刻重新锁定上次攻击者），反击改由 `damage()` 自己计数
- 近战改为优先级 2、3 格内接管（进出都卡距离）：被贴脸打会立刻还手，目标退出后立刻交还远程

### 工程

- 清理 Fabric 模板示例 mixin 与临时日志
- 完善 README 与 `fabric.mod.json` 元数据
