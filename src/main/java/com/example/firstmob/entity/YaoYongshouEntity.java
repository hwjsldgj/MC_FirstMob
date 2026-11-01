package com.example.firstmob.entity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.example.firstmob.Firstmob;
import com.example.firstmob.registry.ModSounds;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.ProjectileAttackGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.LlamaEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustColorTransitionParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import org.joml.Vector3f;

/**
 * 姚永寿：只保留僵尸的外形（由渲染器负责），
 * 行为完全改用羊驼：寻路、跟随商队、远程吐口水；近距离额外加近战。
 */
public class YaoYongshouEntity extends LlamaEntity {
	/** 听觉半径（格）：与羊驼吐口水的最大射程一致，20 格内都算"周围"。 */
	private static final double HEARING_RANGE = 20.0D;
	private static final double HEARING_RANGE_SQUARED = HEARING_RANGE * HEARING_RANGE;
	/** 追击上限：目标跑出 25 格就放弃。 */
	private static final double MAX_CHASE_RANGE = 25.0D;
	private static final double MAX_CHASE_RANGE_SQUARED = MAX_CHASE_RANGE * MAX_CHASE_RANGE;
	/** 计数窗口：只统计最近 5 秒（100 tick）内的声音。 */
	private static final int SOUND_WINDOW_TICKS = 100;
	/** 窗口内声音达到这个次数就主动开火（最少 3 次）。 */
	private static final int SOUND_THRESHOLD = 3;
	/** 窗口内"非脚步"声音低于这个值就停火（同样以 3 次为准）。 */
	private static final int RELEASE_SOUND_COUNT = 3;
	/** 声音分类：走路、跳跃（落地）、受击各自 5 秒只记 1 次；其它声音发生即记 1 次。 */
	private static final int SOUND_WALK = 0;
	private static final int SOUND_JUMP = 1;
	private static final int SOUND_HURT = 2;
	private static final int SOUND_OTHER = 3;
	/** "至少攻击一次"的保证：和计数窗口一样只给 5 秒，安静下来就不再死追。 */
	private static final int GUARANTEE_TIMEOUT_TICKS = 100;
	/** 吐口水冷却：羊驼默认 40 tick，这里减 0.3 秒 = 34 tick。 */
	private static final int SPIT_COOLDOWN_TICKS = 34;
	/** 吐口水最大射程（格），与羊驼默认一致。 */
	private static final double SPIT_RANGE = 20.0D;
	/** 临时诊断日志开关：只记录触发/停火/反击/开炮/声波，定位完就关掉。 */
	private static final boolean DEBUG = true;

	private void debug(String message, Object... args) {
		if (DEBUG) {
			Object[] full = new Object[args.length + 1];
			full[0] = this.getId();
			System.arraycopy(args, 0, full, 1, args.length);
			com.example.firstmob.Firstmob.LOGGER.info("[Yao#{}] " + message, full);
		}
	}
	/** 挨打后的反击上限：每次被打最多还手 3 次，打完就收手。 */
	private static final int REVENGE_SHOT_LIMIT = 3;
	/** 反击超时：5 秒内一次都没打出去（追不到 / 目标无敌）就放弃。 */
	private static final int REVENGE_TIMEOUT_TICKS = 100;
	/** 近战接管距离（3 格）：这个距离内只准近战，远程（口水/声波）一律禁用。 */
	private static final double MELEE_RANGE_SQUARED = 9.0D;
	/** 锁定模式的近战距离（5 格）：比普通模式更早贴脸，5 格内不再吐口水。 */
	private static final double LOCK_MELEE_RANGE_SQUARED = 25.0D;
	/**
	 * 近战伤害类型：data/firstmob/damage_type/yao_melee.json。
	 * <p>`scaling: never` 是必须的——原版 `mob_attack` 在和平难度会被整段清零，
	 * 而姚永寿的近战必须像口水一样照常生效。
	 */
	public static final RegistryKey<DamageType> MELEE_DAMAGE_TYPE =
			RegistryKey.of(RegistryKeys.DAMAGE_TYPE, Firstmob.id("yao_melee"));

	/** 5 秒内声音"超过 8 次"（即 ≥9 次）就升级成监守者声波。 */
	private static final int SONIC_SOUND_THRESHOLD = 9;
	/** 声波模式：目标跑出 40 格才停（比吐口水的 25 格远）。 */
	private static final double SONIC_RANGE = 40.0D;
	private static final double SONIC_RANGE_SQUARED = SONIC_RANGE * SONIC_RANGE;
	/** 原版监守者开炮前蓄力 34 tick。 */
	private static final int SONIC_CHARGE_TICKS = 34;
	/** 两发声波间隔 160 tick（8 秒），比原版监守者的 5 秒慢。 */
	private static final int SONIC_INTERVAL_TICKS = 160;
	/** 原版声波伤害。 */
	private static final float SONIC_DAMAGE = 10.0F;
	/** Boss 掉落经验：普通生物 1~3 点，boss 一次给 500 点。 */
	private static final int BOSS_XP_REWARD = 500;
	/** Boss 血条同步间隔：每 tick 都刷新会不停发包，10 tick（0.5 秒）足够跟手。 */
	private static final int BOSS_BAR_INTERVAL_TICKS = 10;
	/** Boss 形态血量阈值：掉到 80% 以下就变身；退回普通形态后重新获得目标会再次变身。 */
	private static final float BOSS_FORM_HEALTH_RATIO = 0.8F;
	/** Boss 形态体型：运行时写进 generic.scale，碰撞箱、眼高与模型一起放大。 */
	private static final double BOSS_SCALE = 2.0D;
	/** 普通形态体型：退回普通形态时写回。 */
	private static final double NORMAL_SCALE = 1.0D;
	/** 普通形态护甲：5 点（约减伤 20%）。 */
	private static final double NORMAL_ARMOR = 5.0D;
	/** 普通形态没有护甲韧性。 */
	private static final double NORMAL_ARMOR_TOUGHNESS = 0.0D;
	/** Boss 形态护甲：20 点约减伤 80%。 */
	private static final double BOSS_ARMOR = 20.0D;
	/** Boss 形态基础护甲韧性：0；只有低于 20% 血时才拉满。 */
	private static final double BOSS_ARMOR_TOUGHNESS = 0.0D;
	/** 濒死护甲：护甲降到 10、韧性升到 5，用低护甲高韧性硬扛单发重击。 */
	private static final double LOW_HEALTH_ARMOR = 10.0D;
	private static final double LOW_HEALTH_ARMOR_TOUGHNESS = 5.0D;
	/** 脱战回血：玩家连续 10 秒（200 tick）没造成伤害，且未满血时挂生命恢复 I。 */
	private static final int OUT_OF_COMBAT_REGEN_TICKS = 200;
	private static final int OUT_OF_COMBAT_REGEN_AMPLIFIER = 0;
	/** 退回普通形态：解除锁定后连续 30 秒（600 tick）没有目标才触发。 */
	private static final int BOSS_FORM_REVERT_TICKS = 600;
	/** 濒死阈值：低于 20% 触发附魔金苹果效果（每次下穿阈值触发一次）。 */
	private static final float LOW_HEALTH_RATIO = 0.2F;
	/** 伤害吸收 III，2 分钟（现版效果）。 */
	private static final int LOW_HEALTH_ABSORPTION_TICKS = 2400;
	/** 生命恢复 II，30 秒（取 1.8 老版更长的时长）。 */
	private static final int LOW_HEALTH_REGEN_TICKS = 600;
	/** 抗性提升 / 抗火，各 5 分钟（现版效果）。 */
	private static final int LOW_HEALTH_RESISTANCE_TICKS = 6000;
	/** Boss 变身粒子：熔岩橙 → 暗紫的尘团，2 倍体型下也包得住全身。 */
	private static final int BOSS_FORM_PARTICLE_COUNT = 80;
	private static final float BOSS_FORM_PARTICLE_SCALE = 1.5F;
	private static final double BOSS_FORM_PARTICLE_SPREAD = 1.2D;
	private static final Vector3f BOSS_FORM_PARTICLE_FROM = new Vector3f(1.0F, 0.3F, 0.0F);
	private static final Vector3f BOSS_FORM_PARTICLE_TO = new Vector3f(0.35F, 0.0F, 0.5F);
	/** 濒死粒子：原版不死图腾同款，在身体中心炸开。 */
	private static final int LOW_HEALTH_PARTICLE_COUNT = 100;
	private static final double LOW_HEALTH_PARTICLE_HORIZONTAL_SPREAD = 0.6D;
	private static final double LOW_HEALTH_PARTICLE_VERTICAL_SPREAD = 1.0D;
	/** 速度拖尾：任何速度等级（I / II / III）走路都撒烟花，每 4 tick 2 颗，避免 2 倍体型下刷屏。 */
	private static final int SPEED_TRAIL_PARTICLE_INTERVAL_TICKS = 4;
	private static final int SPEED_TRAIL_PARTICLE_COUNT = 2;
	/** "在走路"的判据：脚踩地面且水平速度 > 0.05 格/tick。 */
	private static final double SPEED_TRAIL_MIN_VELOCITY_SQUARED = 0.0025D;
	/** 近战命中扬尘：贴在目标脚下，贴图取脚下方块。 */
	private static final int MELEE_DUST_PARTICLE_COUNT = 6;
	private static final double MELEE_DUST_PARTICLE_SPREAD = 0.35D;
	/** 残血锁定阈值：血量 <60% 开始死咬玩家，≤20 格吐口水、>20 格放声波。 */
	private static final float LOCK_HEALTH_RATIO = 0.6F;
	/** 解除锁定：健康满血且这么久（600 tick = 30 秒）没被玩家打中。 */
	private static final int LOCK_RELEASE_NO_DAMAGE_TICKS = 600;
	/** 空闲游走：始终保持与玩家至少 16 格；32 格内站定注视，>32 格靠近，>48 格传送，落点约 20 格。 */
	private static final double IDLE_LOOK_RANGE_SQUARED = 20.0D * 20.0D;
	private static final double IDLE_KEEP_DISTANCE = 16.0D;
	private static final double IDLE_KEEP_SQUARED = IDLE_KEEP_DISTANCE * IDLE_KEEP_DISTANCE;
	private static final double IDLE_APPROACH_SQUARED = 32.0D * 32.0D;
	private static final double IDLE_TELEPORT_DISTANCE_SQUARED = 48.0D * 48.0D;
	private static final double IDLE_TELEPORT_RADIUS_MIN = 18.0D;
	private static final double IDLE_TELEPORT_RADIUS_MAX = 22.0D;
	/** 玩家周围找不到 3×3×4 落点时的退路：传到 48~64 格外的安全点，不打扰玩家。 */
	private static final double FALLBACK_TELEPORT_RADIUS_MIN = 48.0D;
	private static final double FALLBACK_TELEPORT_RADIUS_MAX = 64.0D;
	/** 传送方向：相对玩家朝向随机偏 35°~145°，左右随机；小于 35° 就是正前方，绝不允许。 */
	private static final double TELEPORT_MIN_ANGLE_DEGREES = 35.0D;
	private static final double TELEPORT_MAX_ANGLE_DEGREES = 145.0D;
	/** 每次传送最多试几个随机落点，全部失败就走退路。 */
	private static final int TELEPORT_ATTEMPTS = 6;
	/** 落点要求 3×3×4 的空地：水平半径 1（宽 3、深 3），高 4 格空气，脚下实心。 */
	private static final int TELEPORT_SPACE_RADIUS = 1;
	private static final int TELEPORT_SPACE_HEIGHT = 4;
	/** 传送失败或退路传送后的重试间隔：5 秒。 */
	private static final int TELEPORT_RETRY_TICKS = 100;
	/** 低于 20% 血时攻击冷却缩短 20%（34→27 tick、160→128 tick、蓄力 34→27）。 */
	private static final double ATTACK_BOOST_COOLDOWN_FACTOR = 0.8D;
	/** 近战挥击动画时长（10 tick = 0.5 秒）：只记开始时的 age，渲染端按插值后的 age 自己算进度。 */
	public static final int ATTACK_ANIM_TICKS = 10;
	/** 挥击开始时的实体 age；-1 表示没在挥击。用 age 而不是倒计时，客户端可以按帧插值，不会一格一格跳。 */
	private static final TrackedData<Integer> ATTACK_START_AGE = DataTracker.registerData(YaoYongshouEntity.class,
			TrackedDataHandlerRegistry.INTEGER);

	/** 每个玩家的全部发声时间（含脚步），用于判断"吵不吵"。 */
	private final Map<UUID, Deque<Long>> recentSounds = new HashMap<>();
	/** 每个玩家的"非脚步"发声时间，用于判断还要不要继续打。 */
	private final Map<UUID, Deque<Long>> recentLoudSounds = new HashMap<>();
	/** 每个玩家上一次走路 / 跳跃 / 受击发声的 tick，用来在 5 秒窗口内各限一次。 */
	private final Map<UUID, Long> lastWalkTicks = new HashMap<>();
	private final Map<UUID, Long> lastJumpTicks = new HashMap<>();
	private final Map<UUID, Long> lastHurtTicks = new HashMap<>();
	/** 当前由"吵闹"触发攻击的目标；null 表示不是声音触发的攻击。 */
	private UUID noiseTarget;
	/** 本次触发还欠几次攻击（至少 1 次）。 */
	private int guaranteedShots;
	/** 本轮触发的时间，用于保证期超时。 */
	private long noiseTriggerTime;
	/** 挨打反击模式：只打回去，计数用完就收手（不无限追）。 */
	private boolean revengeMode;
	/** 本轮反击还剩几次攻击。 */
	private int revengeShots;
	/** 反击超时时间点。 */
	private long revengeDeadline;
	/** 是否处于声波模式：为 true 时只放声波、不吐口水。 */
	private boolean sonicMode;
	/** 距离上一次声波已经过去的 tick 数，用来做蓄力/冷却节拍。 */
	private int sonicTicks;
	/** 是否处于 Boss 形态：血量 <80% 触发；解除锁定后 30 秒没有目标会退回普通形态。 */
	private boolean bossForm;
	/** 退回普通形态后置 true：血量仍 <80% 时保持普通形态，直到重新获得目标才再次变身。 */
	private boolean bossFormDormant;
	/** Boss 形态下连续没有目标的 tick 数，满 600 tick（30 秒）退回普通形态。 */
	private int noTargetTicks;
	/** 本次"低于 20%"是否已结算过金苹果效果，回血到 20% 以上时复位。 */
	private boolean lowHealthTriggered;
	/** 当前是否由脱战回血机制挂着生命恢复 I，用于只移除自己挂的那个效果。 */
	private boolean outOfCombatRegen;
	/** 是否处于残血锁定模式：血量 <60% 进入，满血且 30 秒无伤才解除。 */
	private boolean lockMode;
	/** 锁定目标：优先最近一次打它的玩家，其次是最近的玩家；null 表示没记住谁打过它。 */
	private UUID lockTarget;
	/** 最后一次被玩家打中的世界时间，解锁判定用。 */
	private long lastPlayerDamageTick;
	/** 锁定模式下"距离 >20 格"时的声波节拍（蓄力 / 循环）。 */
	private int lockSonicTicks;
	/** 下一次允许吐口水的世界时间：两种模式共用一套冷却，<20% 血时按 80% 计算。 */
	private long nextSpitTick;
	/** 当前速度等级（-1 无 / 0 速度 I / 1 速度 II / 2 速度 III）。 */
	private int speedLevel = -1;
	/** 下一次允许尝试传送的世界时间。 */
	private long nextTeleportTick;
	/** Boss 血条：标题取实体显示名，进度跟随当前血量，只发给正在追踪本实体的玩家。 */
	private final ServerBossBar bossBar = new ServerBossBar(this.getDisplayName(), BossBar.Color.RED,
			BossBar.Style.PROGRESS);

	public YaoYongshouEntity(EntityType<? extends LlamaEntity> entityType, World world) {
		super(entityType, world);
		// Boss 不随距离自然清除：刷怪蛋/指令生成后一直留在世界里，直到被打死
		this.setPersistent();
	}

	@Override
	protected void initDataTracker(DataTracker.Builder builder) {
		super.initDataTracker(builder);
		builder.add(ATTACK_START_AGE, -1);
	}

	/**
	 * 羊驼在生成时会把最大生命随机成 15~30，覆盖 ModEntities 里配置的 100 点。
	 * 姚永寿的 80% / 60% / 20% 血量阈值都按 100 点设计，这里必须保持原值。
	 */
	@Override
	protected void initAttributes(Random random) {
		// 空实现：不随机最大生命
	}

	/**
	 * 挥击动画进度：0 = 待机，0~1 = 抬臂→抡下→回位。
	 *
	 * @param animationProgress 渲染端传入的 `age + tickDelta`，用它算时间差才能按帧插值
	 */
	public float getAttackAnimationProgress(float animationProgress) {
		int startAge = this.dataTracker.get(ATTACK_START_AGE);
		if (startAge < 0) {
			return 0.0F;
		}
		float elapsed = animationProgress - startAge;
		if (elapsed < 0.0F || elapsed > ATTACK_ANIM_TICKS) {
			return 0.0F;
		}
		return elapsed / ATTACK_ANIM_TICKS;
	}

	// ------------------------------------------------------------------
	// 形态：普通 → Boss → 濒死
	// ------------------------------------------------------------------

	/** 每 tick 判一次血量阈值：<80% 转 Boss 形态（退回后要重新获得目标），<20% 结算附魔金苹果效果。 */
	private void updateFormState() {
		// 死亡那一帧血量已经是 0，别在尸体上再变身/补金苹果
		if (!this.isAlive()) {
			return;
		}
		float ratio = this.getHealth() / this.getMaxHealth();
		if (!this.bossForm) {
			if (ratio >= BOSS_FORM_HEALTH_RATIO) {
				// 回满血：下次掉到 80% 以下仍照常变身
				this.bossFormDormant = false;
			} else if (!this.bossFormDormant || this.getTarget() != null) {
				this.enterBossForm();
			}
		}
		this.updateFormAttributes(ratio);
		if (ratio < LOW_HEALTH_RATIO) {
			if (!this.lowHealthTriggered) {
				this.lowHealthTriggered = true;
				this.applyLowHealthEffects();
				this.spawnLowHealthParticles();
				debug("LOW hp={}", this.getHealth());
			}
		} else {
			// 回血越过 20% 后再掉下去，要能再触发一次
			this.lowHealthTriggered = false;
		}
		// <60%：进入残血锁定，之后不再按声音/距离规则脱战
		if (!this.lockMode && this.getHealth() < this.getMaxHealth() * LOCK_HEALTH_RATIO) {
			this.enterLockMode(this.getWorld().getTime());
		}
	}

	/** 转 Boss 形态：体型写进属性，给永久速度 I、力量 I、抗性提升 I；护甲/韧性由 {@link #updateFormAttributes} 每 tick 维护。 */
	private void enterBossForm() {
		this.bossForm = true;
		this.bossFormDormant = false;
		this.noTargetTicks = 0;
		this.setBaseAttribute(EntityAttributes.GENERIC_SCALE, BOSS_SCALE);
		this.applyBossBuffs();
		this.updateFormAttributes(this.getHealth() / this.getMaxHealth());
		this.spawnBossFormParticles();
		debug("BOSS form enter hp={} strength={} resistance={}", this.getHealth(),
				this.getStatusEffect(StatusEffects.STRENGTH) != null,
				this.getStatusEffect(StatusEffects.RESISTANCE) != null);
	}

	/**
	 * 退回普通形态：体型/护甲/韧性恢复普通数值，收回力量与 Boss 抗性，收起血条。
	 * 只由 {@link #updateBossFormRevert()} 调用；锁定模式期间不会触发。
	 */
	private void exitBossForm() {
		this.bossForm = false;
		this.bossFormDormant = true;
		this.noTargetTicks = 0;
		this.setBaseAttribute(EntityAttributes.GENERIC_SCALE, NORMAL_SCALE);
		this.setBaseAttribute(EntityAttributes.GENERIC_ARMOR, NORMAL_ARMOR);
		this.setBaseAttribute(EntityAttributes.GENERIC_ARMOR_TOUGHNESS, NORMAL_ARMOR_TOUGHNESS);
		// 退回只发生在 ≥60% 血（锁定模式不会退），此时不会有金苹果的抗性在跑
		this.removeStatusEffect(StatusEffects.STRENGTH);
		this.removeStatusEffect(StatusEffects.RESISTANCE);
		this.bossBar.setVisible(false);
		debug("BOSS form exit hp={} armor={} toughness={}", this.getHealth(),
				this.getAttributeValue(EntityAttributes.GENERIC_ARMOR),
				this.getAttributeValue(EntityAttributes.GENERIC_ARMOR_TOUGHNESS));
	}

	/** Boss 形态解除锁定后连续 30 秒（600 tick）没有目标：退回普通形态，等再次获得目标才重新变身。 */
	private void updateBossFormRevert() {
		if (!this.bossForm) {
			this.noTargetTicks = 0;
			return;
		}
		if (this.lockMode || this.getTarget() != null) {
			this.noTargetTicks = 0;
			return;
		}
		if (++this.noTargetTicks >= BOSS_FORM_REVERT_TICKS) {
			this.exitBossForm();
		}
	}

	/**
	 * 脱战回血：玩家连续 10 秒（200 tick）没对姚永寿造成伤害，且未满血时挂生命恢复 I，直到回满。
	 * 再次被玩家打中或回满后立即移除；只移除本机制挂上的那个无限恢复 I，不动金苹果的恢复 II。
	 */
	private void updateOutOfCombatRegen(long now) {
		if (!this.isAlive()) {
			this.clearOutOfCombatRegen();
			return;
		}
		boolean idle = now - this.lastPlayerDamageTick >= OUT_OF_COMBAT_REGEN_TICKS;
		boolean need = idle && this.getHealth() < this.getMaxHealth();
		if (!need) {
			this.clearOutOfCombatRegen();
			return;
		}
		StatusEffectInstance current = this.getStatusEffect(StatusEffects.REGENERATION);
		if (current == null) {
			this.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION,
					StatusEffectInstance.INFINITE, OUT_OF_COMBAT_REGEN_AMPLIFIER, false, false, true));
			this.outOfCombatRegen = true;
			debug("REGEN on hp={}", this.getHealth());
		} else if (current.getAmplifier() == OUT_OF_COMBAT_REGEN_AMPLIFIER
				&& current.getDuration() == StatusEffectInstance.INFINITE) {
			// 读档后补认自己挂的效果，满血/挨打时才能清掉
			this.outOfCombatRegen = true;
		}
	}

	private void clearOutOfCombatRegen() {
		if (!this.outOfCombatRegen) {
			return;
		}
		this.outOfCombatRegen = false;
		StatusEffectInstance current = this.getStatusEffect(StatusEffects.REGENERATION);
		if (current != null && current.getAmplifier() == OUT_OF_COMBAT_REGEN_AMPLIFIER
				&& current.getDuration() == StatusEffectInstance.INFINITE) {
			this.removeStatusEffect(StatusEffects.REGENERATION);
			debug("REGEN off hp={}", this.getHealth());
		}
	}

	/** 护甲/韧性随形态与血量比例实时重算：普通 5/0；Boss 20/0；Boss 且 <20% 血 10/5。 */
	private void updateFormAttributes(float healthRatio) {
		boolean boss = this.bossForm;
		boolean lowHealth = boss && healthRatio < LOW_HEALTH_RATIO;
		double armor = !boss ? NORMAL_ARMOR : (lowHealth ? LOW_HEALTH_ARMOR : BOSS_ARMOR);
		double toughness = !boss ? NORMAL_ARMOR_TOUGHNESS
				: (lowHealth ? LOW_HEALTH_ARMOR_TOUGHNESS : BOSS_ARMOR_TOUGHNESS);
		if (this.getAttributeValue(EntityAttributes.GENERIC_ARMOR) == armor
				&& this.getAttributeValue(EntityAttributes.GENERIC_ARMOR_TOUGHNESS) == toughness) {
			return;
		}
		this.setBaseAttribute(EntityAttributes.GENERIC_ARMOR, armor);
		this.setBaseAttribute(EntityAttributes.GENERIC_ARMOR_TOUGHNESS, toughness);
		debug("FORM armor={} toughness={} boss={} hp={} max={}", armor, toughness, boss, this.getHealth(),
				this.getMaxHealth());
	}

	/** 永久增益：力量 I、抗性提升 I。移动速度统一由 {@link #updateMovementSpeed()} 管理（游走 III / 追击 II / 常态 I）。 */
	private void applyBossBuffs() {
		this.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, StatusEffectInstance.INFINITE, 0,
				false, false, true));
		this.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, StatusEffectInstance.INFINITE, 0,
				false, false, true));
	}

	/** 附魔金苹果：1.8 老版（吸收 I）与 1.9+ 现版（吸收 III + 抗性 + 抗火）合并，恢复 II 取老版 30 秒。 */
	private void applyLowHealthEffects() {
		this.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, LOW_HEALTH_ABSORPTION_TICKS, 2,
				false, true, true));
		this.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, LOW_HEALTH_REGEN_TICKS, 1,
				false, true, true));
		this.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, LOW_HEALTH_RESISTANCE_TICKS, 0,
				false, true, true));
		this.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, LOW_HEALTH_RESISTANCE_TICKS, 0,
				false, true, true));
	}

	/** 改属性基值；只有缩放变了才重算碰撞箱，护甲/韧性每 tick 同步时不白算一遍。 */
	private void setBaseAttribute(RegistryEntry<EntityAttribute> attribute, double value) {
		EntityAttributeInstance instance = this.getAttributeInstance(attribute);
		if (instance == null || instance.getBaseValue() == value) {
			return;
		}
		instance.setBaseValue(value);
		if (attribute == EntityAttributes.GENERIC_SCALE) {
			this.calculateDimensions();
		}
	}

	// ------------------------------------------------------------------
	// 粒子效果（纯表现）：变身尘团、濒死图腾、速度拖尾、近战扬尘
	// 只在服务端生成，由 ServerWorld 广播给追踪者；客户端 tick 提前返回，不会重复生成
	// ------------------------------------------------------------------

	/** 转 Boss 形态当 tick：熔岩橙 → 暗紫的尘团从身体中心炸开。 */
	private void spawnBossFormParticles() {
		if (!(this.getWorld() instanceof ServerWorld serverWorld)) {
			return;
		}
		DustColorTransitionParticleEffect effect = new DustColorTransitionParticleEffect(
				BOSS_FORM_PARTICLE_FROM, BOSS_FORM_PARTICLE_TO, BOSS_FORM_PARTICLE_SCALE);
		serverWorld.spawnParticles(effect, this.getX(), this.getBodyY(0.5D), this.getZ(),
				BOSS_FORM_PARTICLE_COUNT, BOSS_FORM_PARTICLE_SPREAD, BOSS_FORM_PARTICLE_SPREAD,
				BOSS_FORM_PARTICLE_SPREAD, 0.05D);
	}

	/** 濒死效果结算当 tick：不死图腾粒子在身体中心炸开。 */
	private void spawnLowHealthParticles() {
		if (!(this.getWorld() instanceof ServerWorld serverWorld)) {
			return;
		}
		serverWorld.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, this.getX(), this.getBodyY(0.5D), this.getZ(),
				LOW_HEALTH_PARTICLE_COUNT, LOW_HEALTH_PARTICLE_HORIZONTAL_SPREAD,
				LOW_HEALTH_PARTICLE_VERTICAL_SPREAD, LOW_HEALTH_PARTICLE_HORIZONTAL_SPREAD, 0.1D);
	}

	/**
	 * 有速度效果且在地面走路时在脚下拖烟花。
	 * <p>速度 I / II / III 都生效（III 是空闲游走的常驻速度）；站着不动（水平速度不够）不拖。
	 */
	private void updateSpeedTrailParticles() {
		if (this.getStatusEffect(StatusEffects.SPEED) == null) {
			return;
		}
		if (this.age % SPEED_TRAIL_PARTICLE_INTERVAL_TICKS != 0) {
			return;
		}
		if (!this.isOnGround()
				|| this.getVelocity().horizontalLengthSquared() < SPEED_TRAIL_MIN_VELOCITY_SQUARED) {
			return;
		}
		if (!(this.getWorld() instanceof ServerWorld serverWorld)) {
			return;
		}
		// 沿身体宽度随机取一点，2 倍体型下也能盖住双脚
		double offsetX = (this.random.nextDouble() - 0.5D) * this.getWidth();
		double offsetZ = (this.random.nextDouble() - 0.5D) * this.getWidth();
		serverWorld.spawnParticles(ParticleTypes.FIREWORK, this.getX() + offsetX, this.getY() + 0.05D,
				this.getZ() + offsetZ, SPEED_TRAIL_PARTICLE_COUNT, this.getWidth() * 0.1D, 0.02D,
				this.getWidth() * 0.1D, 0.01D);
	}

	/** 近战命中：在目标脚下升起一柱尘，贴图取目标脚下方块。 */
	private void spawnMeleeDustParticles(Entity target) {
		if (!(this.getWorld() instanceof ServerWorld serverWorld)) {
			return;
		}
		BlockPos pos = target.getBlockPos();
		BlockState state = serverWorld.getBlockState(pos.down());
		if (isDustPillarInvisible(state)) {
			state = serverWorld.getBlockState(pos.down(2));
		}
		if (isDustPillarInvisible(state)) {
			state = Blocks.STONE.getDefaultState();
		}
		BlockStateParticleEffect effect = new BlockStateParticleEffect(ParticleTypes.DUST_PILLAR, state);
		serverWorld.spawnParticles(effect, target.getX(), target.getY() + 0.05D, target.getZ(),
				MELEE_DUST_PARTICLE_COUNT, MELEE_DUST_PARTICLE_SPREAD, 0.05D,
				MELEE_DUST_PARTICLE_SPREAD, 0.05D);
	}

	/** dust_pillar 的贴图来自方块模型：空气与流体取不到贴图，会变成空白粒子。 */
	private static boolean isDustPillarInvisible(BlockState state) {
		return state.isAir() || !state.getFluidState().isEmpty();
	}

	// ------------------------------------------------------------------
	// 残血锁定：<60% 血死咬玩家，近处口水、远处声波
	// ------------------------------------------------------------------

	/** 进入锁定模式：清掉声音/反击状态，改由锁定逻辑自己决定打谁、用什么打。 */
	private void enterLockMode(long now) {
		if (this.sonicMode) {
			// 噪音声波是旧机制，锁定后由 updateLockSonic 接管，避免两套状态互相打架
			this.exitSonicMode(now);
		}
		this.lockMode = true;
		this.lockTarget = null;
		this.lockSonicTicks = 0;
		this.nextSpitTick = 0L;
		this.lastPlayerDamageTick = now;
		this.noiseTarget = null;
		this.guaranteedShots = 0;
		this.revengeMode = false;
		this.revengeShots = 0;
		debug("LOCK enter hp={}", this.getHealth());
	}

	/** 解除锁定：目标与节拍全部清空，速度由 {@link #updateMovementSpeed()} 在下一 tick 收回。 */
	private void exitLockMode() {
		this.lockMode = false;
		this.lockTarget = null;
		this.lockSonicTicks = 0;
		debug("LOCK exit hp={}", this.getHealth());
		this.setTarget(null);
	}

	/** 锁定模式每 tick：先判解锁，再解析目标，最后按距离选攻击手段。 */
	private void updateLockMode(long now) {
		// 死亡动画期间尸体还会 tick 一小会儿，别让它继续开炮
		if (!this.isAlive()) {
			return;
		}
		// 满血 + 30 秒没被玩家打中，两个条件同时满足才解除
		if (this.getHealth() >= this.getMaxHealth()
				&& now - this.lastPlayerDamageTick >= LOCK_RELEASE_NO_DAMAGE_TICKS) {
			this.exitLockMode();
			return;
		}
		PlayerEntity target = this.resolveLockTarget();
		if (target == null) {
			this.setTarget(null);
			this.lockSonicTicks = 0;
			return;
		}
		if (this.getTarget() != target) {
			// 换目标：setTarget 在 ≤20 格时会立刻补一枪
			this.setTarget(target);
		}
		double distanceSquared = this.squaredDistanceTo(target);
		if (distanceSquared <= SPIT_RANGE * SPIT_RANGE) {
			// 20 格内：口水，按锁定自己的冷却发（普通模式由 ProjectileAttackGoal 管冷却）
			this.lockSonicTicks = 0;
			if (now >= this.nextSpitTick) {
				this.shootAt(target, 1.0F);
				this.nextSpitTick = now + this.spitCooldownTicks();
			}
		} else {
			this.lockSonicTicks = 0;
			// 20~40 格：声波；40 格以外只追不打，等目标进入射程
			if (distanceSquared <= SONIC_RANGE_SQUARED) {
				this.updateLockSonic(target);
			}
		}
	}

	/** 移动速度：战斗追击速度 II；非战斗游走速度 III；其余按 Boss 形态速度 I / 普通形态无加速。 */
	private void updateMovementSpeed() {
		int desired;
		if (this.isCombatChasing()) {
			desired = 1;
		} else if (!this.lockMode && this.getTarget() == null && this.nearestPlayer() != null) {
			desired = 2;
		} else {
			desired = this.bossForm ? 0 : -1;
		}
		this.setSpeedLevel(desired);
	}

	/** 有目标且还没到近战距离。 */
	private boolean isCombatChasing() {
		LivingEntity target = this.getTarget();
		return target != null && this.squaredDistanceTo(target) > this.meleeRangeSquared();
	}

	/** 切换速度等级（-1 无 / 0 速度 I / 1 速度 II / 2 速度 III）；等级不变就不动效果。 */
	private void setSpeedLevel(int level) {
		if (this.speedLevel == level) {
			return;
		}
		this.speedLevel = level;
		this.removeStatusEffect(StatusEffects.SPEED);
		if (level >= 0) {
			this.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, StatusEffectInstance.INFINITE, level,
					false, false, true));
		}
	}

	/** 当前近战接管距离：锁定模式 5 格（更早贴脸），其余模式 3 格。 */
	private double meleeRangeSquared() {
		return this.lockMode ? LOCK_MELEE_RANGE_SQUARED : MELEE_RANGE_SQUARED;
	}

	/** 目标解析：先找最近一次打它的玩家，找不到再找最近的存活玩家（不限距离）。 */
	private PlayerEntity resolveLockTarget() {
		if (!(this.getWorld() instanceof ServerWorld serverWorld)) {
			return null;
		}
		if (this.lockTarget != null) {
			PlayerEntity attacker = serverWorld.getPlayerByUuid(this.lockTarget);
			if (attacker != null && attacker.isAlive() && !attacker.isSpectator()) {
				return attacker;
			}
			this.lockTarget = null;
		}
		return this.nearestPlayer();
	}

	/** 最近的存活玩家（旁观者不算），没有则 null。 */
	private PlayerEntity nearestPlayer() {
		if (!(this.getWorld() instanceof ServerWorld serverWorld)) {
			return null;
		}
		PlayerEntity nearest = null;
		double nearestSquared = Double.MAX_VALUE;
		for (ServerPlayerEntity player : serverWorld.getPlayers()) {
			if (!player.isAlive() || player.isSpectator()) {
				continue;
			}
			double squared = this.squaredDistanceTo(player);
			if (squared < nearestSquared) {
				nearestSquared = squared;
				nearest = player;
			}
		}
		return nearest;
	}

	// ------------------------------------------------------------------
	// 空闲游走：与玩家保持 16 格以上，>36 格传送到约 20 格
	// ------------------------------------------------------------------

	/** 空闲（无目标、未锁定）时维持 ≥16 格站距：20 格内注视，>20 靠近，<16 后退，>36 传送。 */
	private void updateIdleFollow(long now) {
		if (this.lockMode || this.getTarget() != null) {
			return;
		}
		PlayerEntity player = this.nearestPlayer();
		if (player == null) {
			return;
		}
		double distanceSquared = this.squaredDistanceTo(player);
		if (distanceSquared <= IDLE_LOOK_RANGE_SQUARED) {
			// 20 格内始终注视玩家（LookAtEntityGoal 只有 8 格，覆盖不到这么远）
			this.getLookControl().lookAt(player, 30.0F, 30.0F);
		}
		if (distanceSquared > IDLE_TELEPORT_DISTANCE_SQUARED) {
			if (now >= this.nextTeleportTick) {
				this.tryTeleport(player, now);
			}
			return;
		}
		if (distanceSquared < IDLE_KEEP_SQUARED) {
			// 玩家贴得太近：沿"玩家 → 自己"的方向退到 16 格以外
			this.backOffFromPlayer(player);
		} else if (distanceSquared > IDLE_APPROACH_SQUARED) {
			this.getNavigation().startMovingTo(player, 1.0D);
		} else {
			// 16~32 格的合适距离：站定不动，只看人（游走目标也被 shouldStandStill 拦住）
			this.getNavigation().stop();
		}
	}

	/** 空闲且与玩家在 16~32 格的合适距离内：站定注视，不再游走。 */
	private boolean shouldStandStill() {
		if (this.lockMode || this.getTarget() != null) {
			return false;
		}
		PlayerEntity player = this.nearestPlayer();
		if (player == null) {
			return false;
		}
		double distanceSquared = this.squaredDistanceTo(player);
		return distanceSquared >= IDLE_KEEP_SQUARED && distanceSquared <= IDLE_APPROACH_SQUARED;
	}

	/** 沿"玩家 → 自己"的方向退到 16 格以外。 */
	private void backOffFromPlayer(PlayerEntity player) {
		double dx = this.getX() - player.getX();
		double dz = this.getZ() - player.getZ();
		double length = Math.sqrt(dx * dx + dz * dz);
		if (length < 1.0E-4D) {
			dx = 0.0D;
			dz = 1.0D;
			length = 1.0D;
		}
		double targetX = player.getX() + dx / length * (IDLE_KEEP_DISTANCE + 4.0D);
		double targetZ = player.getZ() + dz / length * (IDLE_KEEP_DISTANCE + 4.0D);
		this.getNavigation().startMovingTo(targetX, this.getY(), targetZ, 1.0D);
	}

	/** 传送：先试玩家周围 ~20 格的侧前方；找不到 3×3×4 落点就退到 48 格外的安全点，5 秒后再试。 */
	private void tryTeleport(PlayerEntity player, long now) {
		if (this.teleportNearPlayer(player)) {
			this.nextTeleportTick = now + 20;
			return;
		}
		this.teleportFarFromPlayer(player);
		this.nextTeleportTick = now + TELEPORT_RETRY_TICKS;
	}

	/**
	 * 传送到玩家侧前方的一处随机落点。
	 * <p>
	 * 每次随机：方向相对玩家朝向偏 35°~145°（左右随机，永远避开正前方）、半径 18~22 格；
	 * 最多试 {@link #TELEPORT_ATTEMPTS} 次，每次上下扫 8 格找 3×3×4 的空地。
	 */
	private boolean teleportNearPlayer(PlayerEntity player) {
		if (!(this.getWorld() instanceof ServerWorld serverWorld)) {
			return false;
		}
		double baseYaw = Math.toRadians(player.getYaw());
		for (int attempt = 0; attempt < TELEPORT_ATTEMPTS; attempt++) {
			double offsetDegrees = TELEPORT_MIN_ANGLE_DEGREES
					+ this.random.nextDouble() * (TELEPORT_MAX_ANGLE_DEGREES - TELEPORT_MIN_ANGLE_DEGREES);
			if (this.random.nextBoolean()) {
				offsetDegrees = -offsetDegrees;
			}
			double radius = IDLE_TELEPORT_RADIUS_MIN
					+ this.random.nextDouble() * (IDLE_TELEPORT_RADIUS_MAX - IDLE_TELEPORT_RADIUS_MIN);
			double angle = baseYaw + Math.toRadians(offsetDegrees);
			double x = player.getX() - Math.sin(angle) * radius;
			double z = player.getZ() + Math.cos(angle) * radius;
			Vec3d landing = this.findLandingSpot(serverWorld, x, player.getY(), z);
			if (landing != null) {
				this.doTeleport(landing);
				debug("TELEPORT near angle={} radius={}", (int) offsetDegrees, (int) radius);
				return true;
			}
		}
		return false;
	}

	/** 玩家周围没有合适落点时的退路：任意方向 48~64 格，尽量不打扰玩家。 */
	private boolean teleportFarFromPlayer(PlayerEntity player) {
		if (!(this.getWorld() instanceof ServerWorld serverWorld)) {
			return false;
		}
		for (int attempt = 0; attempt < TELEPORT_ATTEMPTS; attempt++) {
			double angle = this.random.nextDouble() * Math.PI * 2.0D;
			double radius = FALLBACK_TELEPORT_RADIUS_MIN
					+ this.random.nextDouble() * (FALLBACK_TELEPORT_RADIUS_MAX - FALLBACK_TELEPORT_RADIUS_MIN);
			double x = player.getX() + Math.cos(angle) * radius;
			double z = player.getZ() + Math.sin(angle) * radius;
			Vec3d landing = this.findLandingSpot(serverWorld, x, player.getY(), z);
			if (landing != null) {
				this.doTeleport(landing);
				debug("TELEPORT far radius={}", (int) radius);
				return true;
			}
		}
		return false;
	}

	private void doTeleport(Vec3d landing) {
		this.getNavigation().stop();
		this.requestTeleport(landing.getX(), landing.getY(), landing.getZ());
		this.setVelocity(0.0D, 0.0D, 0.0D);
	}

	/** 在给定水平坐标上下扫 8 格，找 3×3×4 空地的落点；找不到返回 null。 */
	private Vec3d findLandingSpot(ServerWorld world, double x, double baseY, double z) {
		BlockPos.Mutable pos = new BlockPos.Mutable();
		int startY = MathHelper.floor(baseY) + 8;
		int endY = MathHelper.floor(baseY) - 8;
		for (int y = startY; y >= endY; y--) {
			pos.set(x, y, z);
			long chunkPos = ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4);
			if (!world.isChunkLoaded(chunkPos)) {
				continue;
			}
			if (this.isTeleportSpaceClear(world, pos)) {
				return new Vec3d(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
			}
		}
		return null;
	}

	/** 3×3 脚下实心 + 3×3×4 全空气（Boss 形态 2 倍体型也放得下）。 */
	private boolean isTeleportSpaceClear(ServerWorld world, BlockPos origin) {
		for (int dx = -TELEPORT_SPACE_RADIUS; dx <= TELEPORT_SPACE_RADIUS; dx++) {
			for (int dz = -TELEPORT_SPACE_RADIUS; dz <= TELEPORT_SPACE_RADIUS; dz++) {
				BlockPos ground = origin.add(dx, -1, dz);
				if (!world.getBlockState(ground).isSolidBlock(world, ground)) {
					return false;
				}
				for (int dy = 0; dy < TELEPORT_SPACE_HEIGHT; dy++) {
					if (!world.getBlockState(origin.add(dx, dy, dz)).isAir()) {
						return false;
					}
				}
			}
		}
		return true;
	}

	/** 距离 >20 格：蓄力后放声波，节拍与噪音声波一致（<20% 血时也缩短 20%）。 */
	private void updateLockSonic(PlayerEntity target) {
		if (this.lockSonicTicks == 0) {
			this.playSound(ModSounds.SONIC_CHARGE, 3.0F, 1.0F);
		}
		this.faceTarget(target);
		this.lockSonicTicks++;
		if (this.lockSonicTicks == this.sonicChargeTicks()) {
			this.fireSonicBoom(target);
		}
		if (this.lockSonicTicks >= this.sonicIntervalTicks()) {
			this.lockSonicTicks = 0;
		}
	}

	/** <20% 血：攻击冷却降低 20%。 */
	private boolean isAttackBoosted() {
		return this.getHealth() < this.getMaxHealth() * LOW_HEALTH_RATIO;
	}

	/** 吐口水冷却：基础 34 tick，<20% 血时 27 tick。 */
	private int spitCooldownTicks() {
		return this.isAttackBoosted() ? (int) Math.round(SPIT_COOLDOWN_TICKS * ATTACK_BOOST_COOLDOWN_FACTOR)
				: SPIT_COOLDOWN_TICKS;
	}

	/** 声波蓄力：基础 34 tick，<20% 血时 27 tick。 */
	private int sonicChargeTicks() {
		return this.isAttackBoosted() ? (int) Math.round(SONIC_CHARGE_TICKS * ATTACK_BOOST_COOLDOWN_FACTOR)
				: SONIC_CHARGE_TICKS;
	}

	/** 声波循环：基础 160 tick，<20% 血时 128 tick。 */
	private int sonicIntervalTicks() {
		return this.isAttackBoosted() ? (int) Math.round(SONIC_INTERVAL_TICKS * ATTACK_BOOST_COOLDOWN_FACTOR)
				: SONIC_INTERVAL_TICKS;
	}

	/** 形态进存档：重载后不能丢掉 Boss 形态，也不能把「已退回普通形态」读成未变身。 */
	@Override
	public void writeCustomDataToNbt(NbtCompound nbt) {
		super.writeCustomDataToNbt(nbt);
		nbt.putBoolean("BossForm", this.bossForm);
		nbt.putBoolean("BossFormDormant", this.bossFormDormant);
		nbt.putBoolean("LowHealthTriggered", this.lowHealthTriggered);
	}

	@Override
	public void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
		this.bossForm = nbt.getBoolean("BossForm");
		this.bossFormDormant = nbt.getBoolean("BossFormDormant");
		this.lowHealthTriggered = nbt.getBoolean("LowHealthTriggered");
		this.noTargetTicks = 0;
		// 认回读档前挂上的脱战恢复 I，满血/挨打时才能清掉
		StatusEffectInstance regen = this.getStatusEffect(StatusEffects.REGENERATION);
		this.outOfCombatRegen = regen != null && regen.getAmplifier() == OUT_OF_COMBAT_REGEN_AMPLIFIER
				&& regen.getDuration() == StatusEffectInstance.INFINITE;
		// 速度等级由 updateMovementSpeed 每 tick 按状态重算，读档后置空等它接管
		this.speedLevel = -1;
		if (this.bossForm && !this.getWorld().isClient()) {
			// 属性基值随 Attributes 存档；体型与永久增益属于状态，读档时补回来
			this.setBaseAttribute(EntityAttributes.GENERIC_SCALE, BOSS_SCALE);
			this.applyBossBuffs();
		}
	}

	// ------------------------------------------------------------------
	// Boss 血条、经验
	// ------------------------------------------------------------------

	/** 玩家开始追踪本实体时把血条加给他；离开范围或维度时摘掉（与凋灵同一套写法）。 */
	@Override
	public void onStartedTrackingBy(ServerPlayerEntity player) {
		super.onStartedTrackingBy(player);
		this.bossBar.addPlayer(player);
	}

	@Override
	public void onStoppedTrackingBy(ServerPlayerEntity player) {
		super.onStoppedTrackingBy(player);
		this.bossBar.removePlayer(player);
	}

	/** 血条跟随血量，但只有 Boss 形态才显示；按间隔刷新，值没变就不发包。 */
	private void updateBossBar() {
		boolean visible = this.bossForm && this.isAlive() && !this.isRemoved();
		if (this.bossBar.isVisible() != visible) {
			this.bossBar.setVisible(visible);
		}
		if (visible && this.age % BOSS_BAR_INTERVAL_TICKS == 0) {
			float percent = this.getHealth() / this.getMaxHealth();
			if (percent != this.bossBar.getPercent()) {
				this.bossBar.setPercent(percent);
			}
		}
	}

	@Override
	public void onDeath(DamageSource source) {
		super.onDeath(source);
		// 死亡动画期间就收起血条，别在屏幕上留一条空血条
		if (!this.getWorld().isClient()) {
			this.bossBar.setVisible(false);
			this.bossBar.clearPlayers();
		}
	}

	/** Boss 经验：死亡掉落 200 点（原版由击杀者吸收）。 */
	@Override
	protected int getXpToDrop() {
		return BOSS_XP_REWARD;
	}

	// ------------------------------------------------------------------
	// 音效：换成姚永寿自己的字幕（声音文件仍复用原版羊驼/监守者）
	// ------------------------------------------------------------------

	@Override
	protected SoundEvent getAmbientSound() {
		return ModSounds.YAO_AMBIENT;
	}

	@Override
	protected SoundEvent getHurtSound(DamageSource source) {
		return ModSounds.YAO_HURT;
	}

	@Override
	protected SoundEvent getDeathSound() {
		return ModSounds.YAO_DEATH;
	}

	/**
	 * 玩家每发出一次音效就会调用这里（见 {@code PlayerSoundMixin}）。
	 * 走路、跳跃（落地）、受击各算一类，5 秒内每类只记 1 次，且都不维持仇恨。
	 */
	public static void onPlayerSound(PlayerEntity player, SoundEvent sound) {
		World world = player.getWorld();
		if (world.isClient() || !player.isAlive() || player.isSpectator()) {
			return;
		}
		Identifier id = sound.getId();
		String path = id == null ? "" : id.getPath();
		if (path.endsWith(".death")) {
			return;
		}
		// 三类声音各自独立限量：5 秒内每类只记 1 次；其它声音发生即记 1 次
		int kind;
		if (path.endsWith(".step")) {
			kind = SOUND_WALK;
		} else if (path.endsWith(".small_fall") || path.endsWith(".big_fall")) {
			kind = SOUND_JUMP;
		} else if (path.endsWith(".hurt")) {
			kind = SOUND_HURT;
		} else {
			kind = SOUND_OTHER;
		}
		dispatch(player, kind);
	}

	/** 挖方块、与方块交互这些不走音效通道的动作，直接记一次"巨响"（见 {@code PlayerInteractionMixin}）。 */
	public static void onPlayerAction(PlayerEntity player) {
		World world = player.getWorld();
		if (world.isClient() || !player.isAlive() || player.isSpectator()) {
			return;
		}
		dispatch(player, SOUND_OTHER);
	}

	/** 把一次"声音"分发给 20 格内的所有姚永寿。 */
	private static void dispatch(PlayerEntity player, int kind) {
		World world = player.getWorld();
		Box area = player.getBoundingBox().expand(HEARING_RANGE);
		for (YaoYongshouEntity yao : world.getEntitiesByClass(YaoYongshouEntity.class, area,
				candidate -> candidate.squaredDistanceTo(player) <= HEARING_RANGE_SQUARED)) {
			yao.hearSoundFrom(player, kind);
		}
	}

	/** 记账：窗口内声音够多就触发；只有"真噪音"才维持仇恨。 */
	private void hearSoundFrom(PlayerEntity player, int kind) {
		// 已进入残血锁定：目标由锁定逻辑决定，声音不再改仇恨
		if (this.lockMode) {
			return;
		}
		UUID uuid = player.getUuid();
		long now = this.getWorld().getTime();
		boolean loud = kind == SOUND_OTHER;
		// 走路 / 跳跃 / 受击：同一类在 5 秒窗口内只记 1 次；其它声音每次发生都记
		if (!loud) {
			Map<UUID, Long> lastTicks = kind == SOUND_WALK ? this.lastWalkTicks
					: (kind == SOUND_JUMP ? this.lastJumpTicks : this.lastHurtTicks);
			Long last = lastTicks.get(uuid);
			if (last != null && now - last.longValue() < SOUND_WINDOW_TICKS) {
				return;
			}
			lastTicks.put(uuid, now);
		}

		record(this.recentSounds, uuid, now);
		if (loud) {
			record(this.recentLoudSounds, uuid, now);
		}
		// 5 秒内"非脚步"声音超过 8 次：升级成声波模式，优先于吐口水
		if (!this.sonicMode && countInWindow(this.recentLoudSounds, uuid, now) >= SONIC_SOUND_THRESHOLD) {
			this.enterSonicMode(player, now);
			return;
		}
		// 声波模式下不再走吐口水的触发/维持逻辑
		if (this.sonicMode) {
			return;
		}
		if (uuid.equals(this.noiseTarget)) {
			// 已经在打他了，只更新计数
			return;
		}
		if (countInWindow(this.recentLoudSounds, uuid, now) < SOUND_THRESHOLD) {
			// 只有真实动作声才触发：走路/跳跃/受击记在案，但不会把它叫过来
			return;
		}
		this.noiseTarget = uuid;
		this.guaranteedShots = 1;
		this.noiseTriggerTime = now;
		debug("TRIGGER other={}", countInWindow(this.recentLoudSounds, uuid, now));
		// 声音触发的战斗不算"挨打反击"，别被反击计数提前收手
		this.revengeMode = false;
		this.revengeShots = 0;
		// 锁定后交给羊驼自带的 ProjectileAttackGoal：够近就吐口水，太远就先走近
		this.setTarget(player);
	}

	private static void record(Map<UUID, Deque<Long>> map, UUID uuid, long now) {
		Deque<Long> timestamps = map.computeIfAbsent(uuid, key -> new ArrayDeque<>());
		timestamps.addLast(now);
		prune(timestamps, now);
	}

	private static void prune(Deque<Long> timestamps, long now) {
		while (!timestamps.isEmpty() && now - timestamps.peekFirst() > SOUND_WINDOW_TICKS) {
			timestamps.removeFirst();
		}
	}

	private static int countInWindow(Map<UUID, Deque<Long>> map, UUID uuid, long now) {
		Deque<Long> timestamps = map.get(uuid);
		if (timestamps == null) {
			return 0;
		}
		prune(timestamps, now);
		if (timestamps.isEmpty()) {
			map.remove(uuid);
			return 0;
		}
		return timestamps.size();
	}

	private static void pruneOutdated(Map<UUID, Deque<Long>> map, long now) {
		map.entrySet().removeIf(entry -> {
			prune(entry.getValue(), now);
			return entry.getValue().isEmpty();
		});
	}

	@Override
	public void tick() {
		super.tick();
		World world = this.getWorld();
		if (world.isClient()) {
			return;
		}
		this.updateFormState();
		this.updateBossBar();
		long now = world.getTime();
		if (this.lockMode) {
			// 锁定模式：安静停火、25 格放弃、反击超时这些脱战规则全部让位
			this.updateLockMode(now);
		} else {
			// 目标死亡/被移除就立刻收手，别对着尸体重启战斗
			LivingEntity currentTarget = this.getTarget();
			if (currentTarget != null && !currentTarget.isAlive()) {
				this.setTarget(null);
				this.noiseTarget = null;
				this.guaranteedShots = 0;
				this.revengeMode = false;
				this.revengeShots = 0;
			}
			// 反击超时：5 秒内一次都没打出去就放弃，避免目标被永久粘住
			if (this.revengeMode && now > this.revengeDeadline) {
				this.revengeMode = false;
				this.revengeShots = 0;
				this.setAttacker(null);
				this.setTarget(null);
			}
			if (this.sonicMode) {
				this.updateSonicMode(now);
			} else {
				// 目标跑出 25 格：直接放弃追击（声波模式另算，见 updateSonicMode）
				LivingEntity chaseTarget = this.getTarget();
				if (chaseTarget instanceof PlayerEntity player
						&& this.squaredDistanceTo(player) > MAX_CHASE_RANGE_SQUARED) {
					this.setTarget(null);
					this.noiseTarget = null;
					this.guaranteedShots = 0;
				}
			}
			this.updateNoiseAggro(now);
			this.updateIdleFollow(now);
		}
		this.updateBossFormRevert();
		this.updateOutOfCombatRegen(now);
		this.updateMovementSpeed();
		this.updateSpeedTrailParticles();
		if (now % 100L == 0L) {
			pruneOutdated(this.recentSounds, now);
			pruneOutdated(this.recentLoudSounds, now);
		}
	}

	/** 声音触发的攻击：安静下来就停火，但触发后至少要把那一次攻击打完。 */
	private void updateNoiseAggro(long now) {
		if (this.noiseTarget == null || this.sonicMode) {
			return;
		}
		LivingEntity target = this.getTarget();
		if (!(target instanceof PlayerEntity player) || !player.getUuid().equals(this.noiseTarget)) {
			// 目标被换成别人或被清空，声音逻辑从这里退出
			this.noiseTarget = null;
			this.guaranteedShots = 0;
			return;
		}
		boolean guaranteed = this.guaranteedShots > 0;
		if (guaranteed && now - this.noiseTriggerTime > GUARANTEE_TIMEOUT_TICKS) {
			// 被墙挡住 / 追不到，别一直死等
			this.guaranteedShots = 0;
			guaranteed = false;
		}
		// 判断"吵不吵"只看真噪音：走路/跳跃/受击都不维持仇恨
		if (!guaranteed && countInWindow(this.recentLoudSounds, this.noiseTarget, now) < RELEASE_SOUND_COUNT) {
			debug("RELEASE other={}", countInWindow(this.recentLoudSounds, this.noiseTarget, now));
			this.setTarget(null);
			this.noiseTarget = null;
		}
	}

	// ------------------------------------------------------------------
	// 监守者声波：5 秒内发声超过 8 次时触发，与吐口水互斥
	// ------------------------------------------------------------------

	/** 升级成声波模式：锁定目标，目标跑到 40 格外或死亡前不解除。 */
	private void enterSonicMode(PlayerEntity player, long now) {
		this.sonicMode = true;
		this.sonicTicks = 0;
		this.noiseTarget = player.getUuid();
		this.noiseTriggerTime = now;
		this.guaranteedShots = 0;
		this.revengeMode = false;
		this.revengeShots = 0;
		debug("SONIC enter other={}", countInWindow(this.recentLoudSounds, player.getUuid(), now));
		// 这里会立即转向；shootAt 在声波模式下直接返回，不会顺手吐口水
		this.setTarget(player);
	}

	/** 退出声波模式，并清掉这名玩家的声音记录，避免刚出 40 格又被旧记录拉回去。 */
	private void exitSonicMode(long now) {
		// 用记录的 UUID 清账，避免目标被临时抢走时清错人
		if (this.noiseTarget != null) {
			this.recentSounds.remove(this.noiseTarget);
			this.recentLoudSounds.remove(this.noiseTarget);
		}
		this.sonicMode = false;
		this.sonicTicks = 0;
		debug("SONIC exit");
		this.setTarget(null);
		this.noiseTarget = null;
		this.guaranteedShots = 0;
	}

	/** 声波模式下每 tick 走一遍：蓄力 34 tick → 开炮 → 冷却到 160 tick 再来（<20% 血时各缩短 20%）。 */
	private void updateSonicMode(long now) {
		// 直接按 UUID 找锁定目标：中途被别的怪抢仇恨也不会退出声波模式
		PlayerEntity player = this.noiseTarget == null ? null : this.getWorld().getPlayerByUuid(this.noiseTarget);
		if (player == null || !player.isAlive() || this.squaredDistanceTo(player) > SONIC_RANGE_SQUARED) {
			// 只有这两种情况会停：目标死亡（或下线）或离开 40 格
			this.exitSonicMode(now);
			return;
		}
		if (this.getTarget() != player) {
			this.setTarget(player);
		}
		if (this.sonicTicks == 0) {
			this.playSound(ModSounds.SONIC_CHARGE, 3.0F, 1.0F);
		}
		this.faceTarget(player);
		this.sonicTicks++;
		// 声波模式是"9 次噪音"换来的：除贴身近战外一律用它，不再按 20 格切回口水
		if (this.sonicTicks == this.sonicChargeTicks() && this.squaredDistanceTo(player) > this.meleeRangeSquared()) {
			this.fireSonicBoom(player);
		}
		if (this.sonicTicks >= this.sonicIntervalTicks()) {
			this.sonicTicks = 0;
		}
	}

	/** 原版监守者声波：胸口的粒子束 + 10 点穿甲伤害 + 击退，只结算给锁定目标。 */
	private void fireSonicBoom(PlayerEntity target) {
		World world = this.getWorld();
		if (world.isClient()) {
			return;
		}
		Vec3d start = this.getPos().add(0.0D, this.getHeight() * 0.6D, 0.0D);
		Vec3d delta = target.getEyePos().subtract(start);
		Vec3d direction = delta.normalize();
		int steps = MathHelper.floor(delta.length()) + 7;
		if (world instanceof ServerWorld serverWorld) {
			for (int i = 1; i < steps; i++) {
				Vec3d point = start.add(direction.multiply(i));
				serverWorld.spawnParticles(ParticleTypes.SONIC_BOOM, point.getX(), point.getY(), point.getZ(),
						1, 0.0D, 0.0D, 0.0D, 0.0D);
			}
		}
		this.playSound(ModSounds.SONIC_BOOM, 3.0F, 1.0F);
		if (target.damage(world.getDamageSources().sonicBoom(this), SONIC_DAMAGE)) {
			double resistance = 1.0D - target.getAttributeValue(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE);
			double horizontal = 2.5D * resistance;
			double vertical = 0.5D * resistance;
			target.addVelocity(direction.getX() * horizontal, direction.getY() * vertical,
					direction.getZ() * horizontal);
		}
	}

	/** 开火/开炮前把身体和头都转向目标。 */
	private void faceTarget(LivingEntity target) {
		double dx = target.getX() - this.getX();
		double dz = target.getZ() - this.getZ();
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
		this.setYaw(yaw);
		this.bodyYaw = yaw;
		this.headYaw = yaw;
		this.setPitch((float) (-(Math.atan2(target.getBodyY(0.4) - this.getEyeY(), horizontal) * 180.0 / Math.PI)));
	}

	@Override
	protected void initGoals() {
		super.initGoals();
		// 羊驼的商队、绑定/骑乘、繁殖、诱惑、跟随父母等一概不要，只留普通寻路 + 吐口水
		this.goalSelector.clear(goal -> true);
		// 关键：清掉原版 SpitRevengeGoal / ChaseWolvesGoal。RevengeGoal 会在目标被清空后
		// 立刻把"上次攻击者"重新锁回来，导致反击永远停不下来；反击统一由 damage() 自己处理
		this.targetSelector.clear(goal -> true);
		this.goalSelector.add(0, new SwimGoal(this));
		// 近战放优先级 2：只在 3 格内接管（canStart/shouldContinue 都卡距离），
		// 被打时能贴脸还手；目标一退出去就立刻把控制权还给远程
		this.goalSelector.add(2, new CloseMeleeGoal(this, 1.2, true));
		// 远程：太远就走近，进入射程就停下吐口水
		this.goalSelector.add(3, new ProjectileAttackGoal(this, 1.25D, SPIT_COOLDOWN_TICKS, (float) SPIT_RANGE));
		this.goalSelector.add(6, new IdleWanderGoal(this, 1.0D));
		this.goalSelector.add(7, new LookAtEntityGoal(this, PlayerEntity.class, 8.0F));
		this.goalSelector.add(8, new LookAroundGoal(this));
	}

	// ------------------------------------------------------------------
	// 没有幼年形态、不能繁殖、不能驯服/骑乘
	// ------------------------------------------------------------------

	/**
	 * 刷怪蛋有 5% 概率生成幼年个体（{@code PassiveEntity#initialize} 直接改 breedingAge），
	 * 这里把年龄相关状态全部钉死为成年。
	 */
	@Override
	public boolean isBaby() {
		return false;
	}

	@Override
	public void setBaby(boolean baby) {
		// 姚永寿没有幼年形态
	}

	@Override
	public int getBreedingAge() {
		return 0;
	}

	@Override
	public void setBreedingAge(int age) {
		// 始终保持成年
	}

	/**
	 * 不产生后代。
	 * <p>{@code MobEntity.interact} 是 final 的，拿刷怪蛋右键本实体时会走
	 * {@code MobEntity#interactWithItem} → {@code SpawnEggItem#spawnBaby} → 这里，
	 * 而父类 {@code LlamaEntity#createChild} 会造出一只**原版小羊驼**。返回 null 即不生成。
	 */
	@Override
	public LlamaEntity createChild(ServerWorld world, PassiveEntity partner) {
		return null;
	}

	@Override
	public boolean isBreedingItem(ItemStack stack) {
		return false;
	}

	@Override
	public boolean canBreedWith(AnimalEntity other) {
		return false;
	}

	/** 不接受喂食、驯服、上鞍等交互。 */
	@Override
	public ActionResult interactMob(PlayerEntity player, Hand hand) {
		return ActionResult.PASS;
	}

	/** 同类不互殴：任何目标选择器想把同类当目标都会被拦下。 */
	@Override
	public void setTarget(LivingEntity target) {
		if (target instanceof YaoYongshouEntity) {
			return;
		}
		LivingEntity previous = this.getTarget();
		super.setTarget(target);
		if (target == null || target == previous || this.getWorld().isClient()) {
			return;
		}
		// 第一次锁定的瞬间就先打一枪：看得见且在射程内就不等原版的开火倒计时
		if (this.squaredDistanceTo(target) <= SPIT_RANGE * SPIT_RANGE
				&& this.getVisibilityCache().canSee(target)) {
			this.shootAt(target, 1.0F);
			// 记一次冷却，避免锁定模式下一 tick 又补一口
			this.nextSpitTick = this.getWorld().getTime() + this.spitCooldownTicks();
		}
	}

	@Override
	public boolean damage(DamageSource source, float amount) {
		boolean damaged = super.damage(source, amount);
		if (damaged) {
			debug("DAMAGE by {} rev={}/{}", source.getAttacker(), this.revengeMode, this.revengeShots);
			// 被谁打就锁定谁（创造模式玩家也照样反击）；同类造成的伤害不还手
			Entity attacker = source.getAttacker();
			if (attacker instanceof LivingEntity living && living != this && !(living instanceof YaoYongshouEntity)) {
				if (living instanceof PlayerEntity player) {
					// 解锁判据是"玩家造成的伤害"，非玩家打的不刷新这个时间
					this.lastPlayerDamageTick = this.getWorld().getTime();
					if (this.lockMode) {
						// 锁定期间谁打就先咬谁，而且没有还手次数上限
						this.lockTarget = player.getUuid();
						this.setTarget(player);
						return damaged;
					}
				} else if (this.lockMode) {
					// 锁定期间被别的生物打：目标不变，继续咬玩家
					return damaged;
				}
				if (this.sonicMode) {
					if (living == this.getTarget()) {
						// 打人的还是声波锁着的那位：保持锁定，近战由 CloseMeleeGoal 接管
						return damaged;
					}
					if (living instanceof PlayerEntity player) {
						// 换了个玩家贴脸打：声波跟着换目标，不中断
						this.noiseTarget = player.getUuid();
						this.sonicTicks = 0;
						this.guaranteedShots = 0;
						this.setTarget(living);
						return damaged;
					}
					// 非玩家打进来：声波模式结束，回到普通反击
					this.exitSonicMode(this.getWorld().getTime());
				}
				// 挨打引发的仇恨交给原版反击逻辑处理，不再受"安静就停火"的约束；
				// 但反击有上限：每次挨打最多还手 3 次，打完就收手
				this.noiseTarget = null;
				this.guaranteedShots = 0;
				this.revengeMode = true;
				this.revengeShots = REVENGE_SHOT_LIMIT;
				this.revengeDeadline = this.getWorld().getTime() + REVENGE_TIMEOUT_TICKS;
				this.setTarget(living);
			}
		}
		return damaged;
	}

	@Override
	public void shootAt(LivingEntity target, float pullProgress) {
		World world = this.getWorld();
		// 声波模式下声波替代口水：普通模式（未锁定）由噪音决定，锁定模式则不会进入 sonicMode
		if (world.isClient() || this.sonicMode) {
			return;
		}
		// 近距离只准近战：普通模式 3 格、锁定模式 5 格，拉不开距离就不吐口水
		if (this.squaredDistanceTo(target) <= this.meleeRangeSquared()) {
			return;
		}
		// 冷却由调用方控制：普通模式靠 ProjectileAttackGoal，锁定模式靠 nextSpitTick
		debug("SHOOT dist={} rev={}/{} guar={}", (int) this.squaredDistanceTo(target),
				this.revengeMode, this.revengeShots, this.guaranteedShots);
		double dx = target.getX() - this.getX();
		double dz = target.getZ() - this.getZ();
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		double aimY = target.getBodyY(0.4);

		// 开火前先把身体和头都转向玩家
		this.faceTarget(target);

		YaoYongshouSpitEntity spit = new YaoYongshouSpitEntity(EntityType.LLAMA_SPIT, world);
		spit.setOwner(this);
		spit.setPosition(this.getX(), this.getEyeY() - 0.1, this.getZ());

		float speed = 2.5F;
		// 抛物线补偿：飞行时间约 = 水平距离 / 速度，此过程中重力累计下沉 0.5*0.06*t^2
		double t = horizontal / speed;
		double drop = 0.5 * 0.06 * t * t;
		// 速度更高 + 散布更小 = 更准
		spit.setVelocity(dx, aimY - spit.getY() + drop, dz, speed, 0.4F);

		world.playSound(null, this.getX(), this.getY(), this.getZ(),
				ModSounds.YAO_SPIT, net.minecraft.sound.SoundCategory.NEUTRAL, 1.0F, 1.0F);
		world.spawnEntity(spit);

		// 本轮"至少攻击一次"已兑现
		if (this.guaranteedShots > 0 && this.getTarget() instanceof PlayerEntity player
				&& player.getUuid().equals(this.noiseTarget)) {
			this.guaranteedShots--;
		}
		this.countRevengeShot();
	}

	/** 反击计数：每次打出去减 1，用完就清目标收手。 */
	private void countRevengeShot() {
		if (!this.revengeMode) {
			return;
		}
		this.revengeDeadline = this.getWorld().getTime() + REVENGE_TIMEOUT_TICKS;
		if (--this.revengeShots <= 0) {
			this.revengeMode = false;
			// 连"上次攻击者"一起忘掉，避免任何原版逻辑把它重新锁回来
			this.setAttacker(null);
			this.setTarget(null);
		}
	}

	/**
	 * 近战：伤害走自定义类型 {@link #MELEE_DAMAGE_TYPE}（`scaling: never`），和平难度也生效；
	 * 挥击动画与脚下扬尘按"挥击发生"触发，不依赖伤害是否结算成功（创造模式 / 0 伤害也能看到动作）。
	 */
	@Override
	public boolean tryAttack(Entity target) {
		boolean hit;
		if (target instanceof LivingEntity livingTarget) {
			DamageSource source = this.getDamageSources().create(MELEE_DAMAGE_TYPE, this, this);
			float damage = (float) this.getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE);
			hit = livingTarget.damage(source, damage);
		} else {
			hit = super.tryAttack(target);
		}
		// 命中瞬间记录 age：客户端按渲染插值算进度，动画从这一 tick 平滑开始
		this.dataTracker.set(ATTACK_START_AGE, this.age);
		// 命中点的扬尘：dust_pillar 贴在目标脚下，比打在脸上更像"砸出来的"
		this.spawnMeleeDustParticles(target);
		if (hit && target instanceof LivingEntity livingTarget) {
			// 铁傀儡式击退：只往上顶 0.4 × (1 - 击退抗性)，水平方向不额外加（原版 golem 没有 attack_knockback 加成）
			double resistance = Math.max(0.0D,
					1.0D - livingTarget.getAttributeValue(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE));
			livingTarget.setVelocity(livingTarget.getVelocity().add(0.0D, 0.4D * resistance, 0.0D));
		}
		if (!hit) {
			// 创造模式等免疫目标会走到这里；和平难度已由自定义伤害类型解决
			debug("MELEE miss type={} creative={} atk={}", target.getType(),
					target instanceof PlayerEntity player && player.isCreative(),
					this.getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE));
		} else {
			debug("MELEE hit type={} dist2={}", target.getType(), (int) this.squaredDistanceTo(target));
		}
		if (hit) {
			if (this.guaranteedShots > 0 && target instanceof PlayerEntity player
					&& player.getUuid().equals(this.noiseTarget)) {
				this.guaranteedShots--;
			}
			this.countRevengeShot();
		}
		return hit;
	}

	/** 只在贴身距离才触发的近战：优先级 2，进出都由距离把关，不会压制远程吐口水。 */
	private static class CloseMeleeGoal extends MeleeAttackGoal {
		CloseMeleeGoal(PathAwareEntity mob, double speed, boolean pauseWhenMobIdle) {
			super(mob, speed, pauseWhenMobIdle);
		}

		private boolean targetInMeleeRange() {
			LivingEntity target = this.mob.getTarget();
			// 锁定模式用 5 格，普通模式仍是 3 格
			return target != null && this.mob.squaredDistanceTo(target) <= ((YaoYongshouEntity) this.mob).meleeRangeSquared();
		}

		@Override
		public boolean canStart() {
			// 进距离才接管，避免在远处就跟远程抢控制权
			return this.targetInMeleeRange() && super.canStart();
		}

		@Override
		public boolean shouldContinue() {
			// 目标一退出 3 格马上撒手，把控制权还给 ProjectileAttackGoal
			return this.targetInMeleeRange() && super.shouldContinue();
		}
	}

	/** 玩家在 16~20 格的"合适距离"内就站定只看人：把原版游走拦掉。 */
	private static class IdleWanderGoal extends WanderAroundFarGoal {
		IdleWanderGoal(PathAwareEntity mob, double speed) {
			super(mob, speed);
		}

		@Override
		public boolean canStart() {
			return !((YaoYongshouEntity) this.mob).shouldStandStill() && super.canStart();
		}

		@Override
		public boolean shouldContinue() {
			return !((YaoYongshouEntity) this.mob).shouldStandStill() && super.shouldContinue();
		}
	}
}
