package com.example.hush.entity;

import java.util.UUID;
import java.util.function.BiConsumer;

import com.example.hush.Hush;
import com.example.hush.combat.AttackBand;
import com.example.hush.combat.CombatProfile;
import com.example.hush.combat.GazeWatch;
import com.example.hush.combat.SonicCycle;
import com.example.hush.entity.goal.CalmLookAroundGoal;
import com.example.hush.entity.goal.CalmLookAtEntityGoal;
import com.example.hush.entity.goal.CloseMeleeGoal;
import com.example.hush.entity.goal.CuriousLookGoal;
import com.example.hush.entity.goal.IdleWanderGoal;
import com.example.hush.entity.goal.LockAwareProjectileAttackGoal;
import com.example.hush.hearing.AngerLedger;
import com.example.hush.registry.ModEntities;
import com.example.hush.registry.ModSounds;
import com.example.hush.visual.BossBarView;
import com.example.hush.visual.EntityParticles;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.LlamaEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.GameEventTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import net.minecraft.world.event.EntityPositionSource;
import net.minecraft.world.event.GameEvent;
import net.minecraft.world.event.PositionSource;
import net.minecraft.world.event.Vibrations;
import net.minecraft.world.event.listener.EntityGameEventHandler;
import net.minecraft.world.RaycastContext;

/**
 * 姚永寿：只保留僵尸的外形（由渲染器负责），行为沿用羊驼的普通寻路与吐口水，
 * 近距离额外加近战；阶段阈值、伤害与距离全部查 {@link CombatProfile}。
 */
public class YaoYongshouEntity extends LlamaEntity implements Vibrations {
	/** 听觉半径（格）：与羊驼吐口水的最大射程一致，20 格内都算"周围"。 */
	private static final double HEARING_RANGE = 20.0D;
	private static final double HEARING_RANGE_SQUARED = HEARING_RANGE * HEARING_RANGE;
	/** 振动监听半径取整：与音效兜底共用 20 格。 */
	private static final int HEARING_RANGE_BLOCKS = (int) HEARING_RANGE;
	/** 阶段 1 的追击上限：目标跑出 24 格就放弃（值来自 {@link CombatProfile#PHASE_1_GIVE_UP_RANGE}）。 */
	private static final double MAX_CHASE_RANGE = CombatProfile.PHASE_1_GIVE_UP_RANGE;
	private static final double MAX_CHASE_RANGE_SQUARED = MAX_CHASE_RANGE * MAX_CHASE_RANGE;
	/** 脚步 / 落地 / 受击的限频窗口：每类每 5 秒最多算一次真噪音。 */
	private static final int MINOR_NOISE_COOLDOWN_TICKS = 100;
	/** 声音分类：脚步、落地、受击是"小噪音"，其余是"大噪音"。 */
	private static final int NOISE_WALK = 0;
	private static final int NOISE_JUMP = 1;
	private static final int NOISE_HURT = 2;
	private static final int NOISE_LOUD = 3;
	/** 愤怒值：0~100；30 起警觉（口水），100 满（声波）。 */
	private static final int ANGER_MAX = 100;
	private static final int ANGER_AGITATED = 30;
	private static final int ANGER_ANGRY = 100;
	/** 声波退出阈值：低于 90 才从激怒回落，避免刚开一炮就掉档。 */
	private static final int ANGER_ANGRY_EXIT = 90;
	/** 单次真噪音的愤怒：大噪音 12（3 次≈36），小噪音 5（脚步不轻易激怒）。 */
	private static final int ANGER_PER_LOUD_NOISE = 12;
	private static final int ANGER_PER_MINOR_NOISE = 5;
	/** 每 20 tick 愤怒 -1；3 次大噪音约 7 秒内回落，9 次仍能到满。 */
	private static final int ANGER_DECAY_INTERVAL_TICKS = 20;
	/** 好奇：听到声音后看向声源 40 tick，40 tick 内不重复触发。 */
	private static final int CURIOUS_DURATION_TICKS = 40;
	/** 音效补充与 GameEvent 的配对窗口：同一次动作的音效和事件间隔不超过 2 tick。 */
	private static final int SUPPLEMENT_PAIR_TICKS = 2;
	/**
	 * 诊断日志开关：默认关闭，避免正式包刷日志；排查时用系统属性
	 * {@code -Dhush.debug=true} 打开（实例化前设置，全局生效）。
	 */
	private static final boolean DEBUG = Boolean.getBoolean("hush.debug");

	private void debug(String message, Object... args) {
		if (DEBUG) {
			Object[] full = new Object[args.length + 1];
			full[0] = this.getId();
			System.arraycopy(args, 0, full, 1, args.length);
			com.example.hush.Hush.LOGGER.info("[Yao#{}] " + message, full);
		}
	}
	/** 所有阶段的近战接管距离都是 5 格（阶段 1 也放宽到 5 格）。 */
	private static final double MELEE_RANGE_SQUARED = CombatProfile.MELEE_RANGE * CombatProfile.MELEE_RANGE;
	/**
	 * 近战伤害类型：data/hush/damage_type/yao_melee.json。
	 * <p>`scaling: never` 是必须的——原版 `mob_attack` 在和平难度会被整段清零，
	 * 而姚永寿的近战必须像口水一样照常生效。
	 */
	public static final RegistryKey<DamageType> MELEE_DAMAGE_TYPE =
			RegistryKey.of(RegistryKeys.DAMAGE_TYPE, Hush.id("yao_melee"));
	/** 原版监守者开炮前蓄力 34 tick、两发间隔 160 tick，见 {@link SonicCycle}。 */
	/** Boss 掉落经验：普通生物 1~3 点，boss 一次给 500 点。 */
	private static final int BOSS_XP_REWARD = 500;
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
	/** 传送失败或退路传送后的重试间隔：5 秒。 */
	private static final int TELEPORT_RETRY_TICKS = 100;
	/** 近战挥击动画时长（10 tick = 0.5 秒）：只记开始时的 age，渲染端按插值后的 age 自己算进度。 */
	public static final int ATTACK_ANIM_TICKS = 10;
	/** 挥击开始时的实体 age；-1 表示没在挥击。用 age 而不是倒计时，客户端可以按帧插值，不会一格一格跳。 */
	private static final TrackedData<Integer> ATTACK_START_AGE = DataTracker.registerData(YaoYongshouEntity.class,
			TrackedDataHandlerRegistry.INTEGER);

	/** 愤怒、听觉配对与限频的账本：纯逻辑，有单测（AngerLedgerTest）。 */
	private final AngerLedger ledger = new AngerLedger();
	/** 好奇：看向声源的位置与截止 tick。 */
	private Vec3d curiousPos;
	private long curiousUntil;
	private long nextCuriousAllowed;
	/** 声波模式锁定的玩家；null 表示没有。 */
	private UUID sonicTarget;
	/** 监守者式振动监听的待处理状态（距离延迟、当前选中的振动）。 */
	private final Vibrations.ListenerData vibrationListenerData = new Vibrations.ListenerData();
	private final Vibrations.Callback vibrationCallback;
	private final EntityGameEventHandler<Vibrations.VibrationListener> gameEventHandler;
	/** 挨打反击模式：只打回去，计数用完就收手（不无限追）。 */
	private boolean revengeMode;
	/** 本轮反击还剩几次攻击。 */
	private int revengeShots;
	/** 反击超时时间点。 */
	private long revengeDeadline;
	/** 是否处于声波模式：为 true 时只放声波、不吐口水。 */
	private boolean sonicMode;
	/** 阶段 1 声波的蓄力 / 循环节拍。 */
	private final SonicCycle sonicCycle = new SonicCycle();
	/** 是否处于 Boss 形态：血量 <80% 触发；解除锁定后 30 秒没有目标会退回普通形态。 */
	private boolean bossForm;
	/** 退回普通形态后置 true：血量仍 <80% 时保持普通形态，直到重新获得目标才再次变身。 */
	private boolean bossFormDormant;
	/** Boss 形态下连续没有目标的 tick 数，满 600 tick（30 秒）退回普通形态。 */
	private int noTargetTicks;
	/** 当前阶段：1~4，由 Boss 形态与当前血量比例每 tick 计算。 */
	private int phase = 1;
	/** 上一 tick 的血量比例，用来判断「跌破 30%」这个事件。 */
	private float previousHealthRatio = 1.0F;
	/** 下一次允许触发阶段 4 额外增益的世界时间。 */
	private long nextPhase4BuffTick;
	/** 当前是否挂着本机制给 Boss 的抗性提升 / 力量。 */
	private boolean resistanceBuff;
	private boolean strengthBuff;
	/** 当前是否由脱战回血机制挂着生命恢复 I，用于只移除自己挂的那个效果。 */
	private boolean outOfCombatRegen;
	/** 阶段 3/4 的硬锁目标：优先最近一次打它的玩家，其次是最近的玩家。 */
	private UUID lockTarget;
	/** 最后一次被玩家打中的世界时间，脱战回血判定用。 */
	private long lastPlayerDamageTick;
	/** 注视判定的采样间隔：5 tick（0.25 秒），最多晚这么久才发现玩家开始/停止注视。 */
	private static final int GAZE_CHECK_INTERVAL_TICKS = 5;

	/** 上次打 GAZE 调试日志的游戏刻（20 tick 节流），供冒烟测试做可观测证据。 */
	private long lastGazeLogTick;
	/** 上次注视判定的游戏刻与结果，避免每 tick 对每个玩家跑一次射线。 */
	private long lastGazeCheckTick;
	private boolean lastGazeResult;
	/** Boss 阶段声波的蓄力 / 循环节拍（与阶段 1 各自独立）。 */
	private final SonicCycle bossSonicCycle = new SonicCycle();
	/** 下一次允许吐口水的世界时间，各阶段共用一套冷却。 */
	private long nextSpitTick;
	/** 当前速度等级（-1 无 / 0 速度 I / 1 速度 II / 2 速度 III）。 */
	private int speedLevel = -1;
	/** 下一次允许尝试传送的世界时间。 */
	private long nextTeleportTick;
	/** Boss 血条：标题取实体显示名，进度跟随当前血量，只发给正在追踪本实体的玩家。 */
	private final BossBarView bossBar = new BossBarView(this.getDisplayName());

	public YaoYongshouEntity(EntityType<? extends LlamaEntity> entityType, World world) {
		super(entityType, world);
		// Boss 不随距离自然清除：刷怪蛋/指令生成后一直留在世界里，直到被打死
		this.setPersistent();
		this.vibrationCallback = new YaoVibrationCallback();
		this.gameEventHandler = new EntityGameEventHandler<>(new Vibrations.VibrationListener(this));
	}

	@Override
	public Vibrations.ListenerData getVibrationListenerData() {
		return this.vibrationListenerData;
	}

	@Override
	public Vibrations.Callback getVibrationCallback() {
		return this.vibrationCallback;
	}

	/**
	 * 把振动监听器挂到所在区块段的 GameEvent 分发器上。
	 * 与原版监守者一致：服务端追踪实体时由 {@code updateEventHandler} 注册，卸载时注销。
	 */
	@Override
	public void updateEventHandler(BiConsumer<EntityGameEventHandler<?>, ServerWorld> callback) {
		if (this.getWorld() instanceof ServerWorld serverWorld) {
			callback.accept(this.gameEventHandler, serverWorld);
		}
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
	// 阶段：普通（阶段 1）→ Boss（阶段 2/3/4）
	// ------------------------------------------------------------------

	/** 每 tick 判血量阈值：<80% 转 Boss 形态；阶段按当前血量动态取档（Boss 形态下限为 2）。 */
	private void updateFormState() {
		// 死亡那一帧血量已经是 0，别在尸体上再变身
		if (!this.isAlive()) {
			return;
		}
		float ratio = this.getHealth() / this.getMaxHealth();
		if (!this.bossForm) {
			if (ratio >= CombatProfile.PHASE_2_HEALTH_RATIO) {
				// 回满血：下次掉到 80% 以下仍照常变身
				this.bossFormDormant = false;
			} else if (!this.bossFormDormant || this.getTarget() != null) {
				this.enterBossForm();
			}
		}
		this.phase = this.combatPhaseFor(ratio);
		this.updateFormAttributes();
		this.updatePhaseBuffs();
		this.updatePhase4Trigger(ratio);
		this.previousHealthRatio = ratio;
	}

	/** 阶段取档：Boss 形态至少是 2；回血跨过阈值会退回低阶段。 */
	private int combatPhaseFor(float ratio) {
		return CombatProfile.phaseFor(ratio, this.bossForm);
	}

	/** 阶段 4：血量从 30% 以上跌破 30% 时触发额外增益，冷却 1 分钟，可重复。 */
	private void updatePhase4Trigger(float ratio) {
		if (!this.bossForm || ratio > CombatProfile.PHASE_4_HEALTH_RATIO
				|| this.previousHealthRatio <= CombatProfile.PHASE_4_HEALTH_RATIO) {
			return;
		}
		long now = this.getWorld().getTime();
		if (now < this.nextPhase4BuffTick) {
			return;
		}
		this.nextPhase4BuffTick = now + CombatProfile.PHASE_4_BUFF_COOLDOWN_TICKS;
		this.applyPhase4Buffs();
		EntityParticles.phase4(this);
		debug("PHASE4 buff hp={}", this.getHealth());
	}


	/** 转 Boss 形态：体型写进属性，护甲/增益由每 tick 的阶段维护逻辑接管。 */
	private void enterBossForm() {
		this.bossForm = true;
		this.bossFormDormant = false;
		this.noTargetTicks = 0;
		this.curiousUntil = 0L;
		this.setBaseAttribute(EntityAttributes.GENERIC_SCALE, CombatProfile.BOSS_SCALE);
		this.phase = Math.max(2, this.combatPhaseFor(this.getHealth() / this.getMaxHealth()));
		this.updateFormAttributes();
		this.updatePhaseBuffs();
		EntityParticles.bossForm(this);
		debug("BOSS form enter hp={} phase={}", this.getHealth(), this.phase);
	}

	/**
	 * 退回普通形态：体型/护甲恢复普通数值，收回本机制给的力量与抗性，收起血条。
	 * 只由 {@link #updateBossFormRevert()} 调用。
	 */
	private void exitBossForm() {
		this.bossForm = false;
		this.bossFormDormant = true;
		this.noTargetTicks = 0;
		this.phase = 1;
		this.setBaseAttribute(EntityAttributes.GENERIC_SCALE, CombatProfile.NORMAL_SCALE);
		this.setBaseAttribute(EntityAttributes.GENERIC_ARMOR, CombatProfile.armor(1));
		this.setBaseAttribute(EntityAttributes.GENERIC_ARMOR_TOUGHNESS, CombatProfile.ARMOR_TOUGHNESS);
		this.removePhaseBuffs();
		// Boss 期间强制的满愤怒不带到普通形态，避免一退回就接着放声波
		this.ledger.clearHearing();
		this.bossBar.hide();
		debug("BOSS form exit hp={} armor={}", this.getHealth(),
				this.getAttributeValue(EntityAttributes.GENERIC_ARMOR));
	}

	/** Boss 形态连续 30 秒（600 tick）没有目标：退回普通形态，等再次获得目标才重新变身。 */
	private void updateBossFormRevert() {
		if (!this.bossForm) {
			this.noTargetTicks = 0;
			return;
		}
		if (this.getTarget() != null) {
			this.noTargetTicks = 0;
			return;
		}
		if (++this.noTargetTicks >= CombatProfile.BOSS_FORM_REVERT_TICKS) {
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
		boolean idle = now - this.lastPlayerDamageTick >= CombatProfile.OUT_OF_COMBAT_REGEN_TICKS;
		boolean need = idle && this.getHealth() < this.getMaxHealth();
		if (!need) {
			this.clearOutOfCombatRegen();
			return;
		}
		StatusEffectInstance current = this.getStatusEffect(StatusEffects.REGENERATION);
		if (current == null) {
			this.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION,
					StatusEffectInstance.INFINITE, CombatProfile.OUT_OF_COMBAT_REGEN_AMPLIFIER, false, false, true));
			this.outOfCombatRegen = true;
			debug("REGEN on hp={}", this.getHealth());
		} else if (current.getAmplifier() == CombatProfile.OUT_OF_COMBAT_REGEN_AMPLIFIER
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
		if (current != null && current.getAmplifier() == CombatProfile.OUT_OF_COMBAT_REGEN_AMPLIFIER
				&& current.getDuration() == StatusEffectInstance.INFINITE) {
			this.removeStatusEffect(StatusEffects.REGENERATION);
			debug("REGEN off hp={}", this.getHealth());
		}
	}

	/** 护甲/韧性/攻击力按当前阶段实时重算：5/10/15/20，韧性恒 0。 */
	private void updateFormAttributes() {
		double armor = CombatProfile.armor(this.phase);
		double attackDamage = this.meleeDamage();
		if (this.getAttributeValue(EntityAttributes.GENERIC_ARMOR) == armor
				&& this.getAttributeValue(EntityAttributes.GENERIC_ARMOR_TOUGHNESS) == CombatProfile.ARMOR_TOUGHNESS
				&& this.getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE) == attackDamage) {
			return;
		}
		this.setBaseAttribute(EntityAttributes.GENERIC_ARMOR, armor);
		this.setBaseAttribute(EntityAttributes.GENERIC_ARMOR_TOUGHNESS, CombatProfile.ARMOR_TOUGHNESS);
		this.setBaseAttribute(EntityAttributes.GENERIC_ATTACK_DAMAGE, attackDamage);
		debug("FORM phase={} armor={} attack={} hp={}", this.phase, armor, attackDamage, this.getHealth());
	}

	/** Boss 阶段常驻：阶段 2+ 抗性提升 I（直到不再是 Boss），阶段 3+ 再加力量 I。 */
	private void updatePhaseBuffs() {
		if (this.bossForm) {
			this.ensureInfiniteEffect(StatusEffects.RESISTANCE);
			this.resistanceBuff = true;
		} else if (this.resistanceBuff) {
			this.removeStatusEffect(StatusEffects.RESISTANCE);
			this.resistanceBuff = false;
		}
		if (this.phase >= 3) {
			this.ensureInfiniteEffect(StatusEffects.STRENGTH);
			this.strengthBuff = true;
		} else if (this.strengthBuff) {
			this.removeStatusEffect(StatusEffects.STRENGTH);
			this.strengthBuff = false;
		}
	}

	private void ensureInfiniteEffect(RegistryEntry<StatusEffect> effect) {
		if (this.getStatusEffect(effect) == null) {
			this.addStatusEffect(new StatusEffectInstance(effect, StatusEffectInstance.INFINITE, 0,
					false, false, true));
		}
	}

	/** 只移除本机制挂上的力量/抗性（退回普通形态时调用）。 */
	private void removePhaseBuffs() {
		if (this.strengthBuff) {
			this.removeStatusEffect(StatusEffects.STRENGTH);
			this.strengthBuff = false;
		}
		if (this.resistanceBuff) {
			this.removeStatusEffect(StatusEffects.RESISTANCE);
			this.resistanceBuff = false;
		}
	}

	/** 阶段 4 额外增益：抗性提升 I、生命恢复 II、速度 I、力量 I、伤害吸收 I，各 3 分钟。 */
	private void applyPhase4Buffs() {
		this.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, CombatProfile.PHASE_4_BUFF_TICKS, 0,
				false, true, true));
		this.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, CombatProfile.PHASE_4_BUFF_TICKS, 1,
				false, true, true));
		this.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, CombatProfile.PHASE_4_BUFF_TICKS, 0,
				false, true, true));
		this.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, CombatProfile.PHASE_4_BUFF_TICKS, 0,
				false, true, true));
		this.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, CombatProfile.PHASE_4_BUFF_TICKS, 0,
				false, true, true));
	}

	// ------------------------------------------------------------------
	// 阶段攻击档案：伤害、穿甲、击退、距离分段
	// ------------------------------------------------------------------

	/** 近战总伤害：5 / 8 / 8 / 10（表值见 {@link CombatProfile}）。 */
	private float meleeDamage() {
		return (float) CombatProfile.meleeDamage(this.phase);
	}

	/** 近战中无视护甲的伤害：0 / 0 / 4 / 5。 */
	private float meleePiercing() {
		return (float) CombatProfile.meleePiercing(this.phase);
	}

	/** 口水伤害：3 / 4 / 5 / 6。 */
	private float spitDamage() {
		return (float) CombatProfile.spitDamage(this.phase);
	}

	/** 口水穿甲：0 / 0 / 3 / 4；阶段 4 的 17~24 随机口水是 8 点、无穿甲。 */
	private float spitPiercing() {
		return (float) CombatProfile.spitPiercing(this.phase);
	}

	/** 口水击退：阶段 1 无，阶段 2 为 I，阶段 3/4 为 II。 */
	private double spitKnockback() {
		return CombatProfile.spitKnockback(this.phase);
	}

	/** 声波伤害：6 / 8 / 10 / 14。 */
	private float sonicDamage() {
		return (float) CombatProfile.sonicDamage(this.phase);
	}

	/** 追到多远停下：8 / 16 / 16 / 32。 */
	private double chaseStopRangeSquared() {
		double range = CombatProfile.chaseStopRange(this.phase);
		return range * range;
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
	// 粒子效果（纯表现）：变身尘团、阶段 4 图腾、速度拖尾、近战扬尘
	// 只在服务端生成，由 ServerWorld 广播给追踪者；客户端 tick 提前返回，不会重复生成
	// ------------------------------------------------------------------


	// ------------------------------------------------------------------
	// Boss 阶段（2/3/4）：硬锁玩家，按阶段距离分工
	// ------------------------------------------------------------------

	/** Boss 阶段每 tick：解析目标、按阶段走位、按距离选择近战/口水/声波。 */
	private void updateBossCombat(long now) {
		// 死亡动画期间尸体还会 tick 一小会儿，别让它继续开炮
		if (!this.isAlive()) {
			return;
		}
		PlayerEntity target = this.resolveBossTarget();
		if (target == null) {
			this.stopSonicMode();
			this.bossSonicCycle.reset();
			this.setTarget(null);
			this.getNavigation().stop();
			return;
		}
		this.revengeMode = false;
		this.revengeShots = 0;
		if (this.getTarget() != target) {
			// 换目标：setTarget 在射程内会立刻补一枪
			this.setTarget(target);
		}
		double distanceSquared = this.squaredDistanceTo(target);
		// 走位：超过追停距离才靠近，否则站定；阶段 3/4 永不脱锁。
		if (distanceSquared > this.chaseStopRangeSquared()) {
			this.getNavigation().startMovingTo(target, 1.0D);
		} else {
			this.getNavigation().stop();
		}
		// 声波蓄力一旦开始，先把这一发打完：期间不切近战/口水
		if (this.isSonicCharging()) {
			this.updateBossSonic(target);
			return;
		}
		switch (AttackBand.forDistance(this.phase, distanceSquared)) {
			case MELEE -> // 5 格内只近战
					this.bossSonicCycle.reset();
			case SPIT -> {
				// 6~16（阶段 1 为 6~24）：吐口水
				this.bossSonicCycle.reset();
				if (now >= this.nextSpitTick) {
					this.shootAt(target, 1.0F);
					this.nextSpitTick = now + CombatProfile.SPIT_COOLDOWN_TICKS;
				}
			}
			case RANDOM -> {
				// 阶段 4 的 17~24 格：口水/声波 3:1，均带击退
				this.bossSonicCycle.reset();
				if (now >= this.nextSpitTick) {
					this.nextSpitTick = now + CombatProfile.SPIT_COOLDOWN_TICKS;
					if (this.random.nextFloat() < CombatProfile.RANDOM_SPIT_CHANCE) {
						this.shootSpit(target, CombatProfile.RANDOM_SPIT_DAMAGE, 0.0F, this.spitKnockback());
					} else {
						this.fireSonicBoom(target, CombatProfile.RANDOM_SONIC_DAMAGE);
					}
				}
			}
			case SONIC -> // 声波段：17~32 / 17~48 / 25~64，边追边打
					this.updateBossSonic(target);
			case CHASE_ONLY -> // 距离在声波攻击区间之外（阶段 4 >64 格）：只追不打
					this.bossSonicCycle.reset();
		}
	}

	/** 移动速度：空闲游走 III；阶段 1 无基础速度、追逐 I；阶段 2+ 基础 I、追逐 II；阶段 4 >64 格追逐 III。 */
	private void updateMovementSpeed() {
		LivingEntity target = this.getTarget();
		int desired;
		if (target == null) {
			desired = !this.bossForm && this.nearestPlayer() != null ? 2 : (this.bossForm ? 0 : -1);
		} else {
			int base = this.bossForm ? 0 : -1;
			boolean chasing = this.squaredDistanceTo(target) > this.meleeRangeSquared();
			desired = chasing ? base + 1 : base;
			if (this.phase == 4 && chasing
					&& this.squaredDistanceTo(target) > CombatProfile.CHASE_ONLY_RANGE * CombatProfile.CHASE_ONLY_RANGE) {
				desired += 1;
			}
		}
		this.setSpeedLevel(desired);
	}

	/** 切换速度等级（-1 无 / 0 速度 I / 1 速度 II / 2 速度 III）；等级与效果都符合才不动。 */
	private void setSpeedLevel(int level) {
		StatusEffectInstance current = this.getStatusEffect(StatusEffects.SPEED);
		boolean infinite = current != null && current.getDuration() == StatusEffectInstance.INFINITE;
		if (this.speedLevel == level && (level < 0 ? current == null : infinite)) {
			return;
		}
		this.speedLevel = level;
		this.removeStatusEffect(StatusEffects.SPEED);
		if (level >= 0) {
			this.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, StatusEffectInstance.INFINITE, level,
					false, false, true));
		}
	}

	/** 所有阶段的近战接管距离都是 5 格。 */
	public double meleeRangeSquared() {
		return MELEE_RANGE_SQUARED;
	}

	/** Boss 阶段目标解析：阶段 3/4 优先上次攻击者，其次当前/最近玩家；阶段 2 超 32 格脱锁。 */
	private PlayerEntity resolveBossTarget() {
		if (!(this.getWorld() instanceof ServerWorld serverWorld)) {
			return null;
		}
		if (this.phase >= 3 && this.lockTarget != null) {
			PlayerEntity attacker = serverWorld.getPlayerByUuid(this.lockTarget);
			if (attacker != null && attacker.isAlive() && !attacker.isSpectator()) {
				return attacker;
			}
			this.lockTarget = null;
		}
		PlayerEntity current = this.getTarget() instanceof PlayerEntity player && player.isAlive()
				&& !player.isSpectator() ? player : null;
		double giveUp = CombatProfile.giveUpRange(this.phase);
		double giveUpSquared = giveUp * giveUp;
		if (current != null && this.squaredDistanceTo(current) <= giveUpSquared) {
			return current;
		}
		PlayerEntity nearest = this.nearestPlayer();
		if (nearest != null && this.squaredDistanceTo(nearest) <= giveUpSquared) {
			return nearest;
		}
		return null;
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
	// 空闲游走：与玩家保持 16 格以上，>48 格传送到侧前方约 20 格
	// ------------------------------------------------------------------

	/** 空闲（无目标、未变身）时维持 ≥16 格站距：20 格内注视，>20 靠近，<16 后退，>36 传送。 */
	private void updateIdleFollow(long now) {
		if (this.bossForm || this.getTarget() != null) {
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
	public boolean shouldStandStill() {
		if (this.bossForm || this.getTarget() != null) {
			return false;
		}
		// 仅非战斗（无目标）时生效：被注视就不游走
		if (this.isWatchedByPlayer()) {
			return true;
		}
		PlayerEntity player = this.nearestPlayer();
		if (player == null) {
			return false;
		}
		double distanceSquared = this.squaredDistanceTo(player);
		return distanceSquared >= IDLE_KEEP_SQUARED && distanceSquared <= IDLE_APPROACH_SQUARED;
	}

	/**
	 * 非 Boss 形态下是否有玩家正在注视自己（{@link GazeWatch} 的角度/距离规则 + 方块遮挡）。
	 * <p>只看主世界服务端玩家；旁观者、死亡玩家不参与判定。
	 * <p>每 {@value #GAZE_CHECK_INTERVAL_TICKS} tick 采样一次，最多 0.25 秒延迟。
	 */
	public boolean isWatchedByPlayer() {
		if (this.bossForm || !(this.getWorld() instanceof ServerWorld world)) {
			return false;
		}
		long now = world.getTime();
		if (now - this.lastGazeCheckTick < GAZE_CHECK_INTERVAL_TICKS) {
			return this.lastGazeResult;
		}
		this.lastGazeCheckTick = now;
		this.lastGazeResult = this.scanGaze(world);
		return this.lastGazeResult;
	}

	private boolean scanGaze(ServerWorld world) {
		Vec3d eye = this.getEyePos();
		for (PlayerEntity player : world.getPlayers()) {
			if (!player.isAlive() || player.isSpectator()) {
				continue;
			}
			Vec3d from = player.getEyePos();
			Vec3d look = player.getRotationVec(1.0F);
			if (!GazeWatch.isWatching(eye.x - from.x, eye.y - from.y, eye.z - from.z,
					look.x, look.y, look.z, Math.sqrt(player.squaredDistanceTo(this)))) {
				continue;
			}
			if (!isSightBlocked(world, from, eye, player)) {
				return true;
			}
		}
		return false;
	}

	/** 连线上有方块碰撞箱时算挡住；玻璃、树叶这类有碰撞箱的方块也会挡住。 */
	private static boolean isSightBlocked(ServerWorld world, Vec3d from, Vec3d to, Entity source) {
		BlockHitResult hit = world.raycast(new RaycastContext(from, to,
				RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, source));
		return hit.getType() != HitResult.Type.MISS;
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
			Vec3d landing = TeleportSearch.findLandingSpot(serverWorld, x, player.getY(), z);
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
			Vec3d landing = TeleportSearch.findLandingSpot(serverWorld, x, player.getY(), z);
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


	/** 声波蓄力已经开始但还没开炮：这段时间不切换近战/口水，先把这一发打完。 */
	public boolean isSonicCharging() {
		if (!this.bossForm) {
			return this.sonicMode && this.sonicCycle.isCharging();
		}
		return this.bossSonicCycle.isCharging();
	}

	/** Boss 阶段：蓄力 34 tick 后按阶段伤害开炮，循环 160 tick。 */
	private void updateBossSonic(PlayerEntity target) {
		if (this.bossSonicCycle.isIdle()) {
			this.playSound(ModSounds.SONIC_CHARGE, 3.0F, 1.0F);
		}
		this.faceTarget(target);
		if (this.bossSonicCycle.tick()) {
			this.fireSonicBoom(target, this.sonicDamage());
			debug("BOSS sonic fire dist={} phase={}", (int) Math.sqrt(this.squaredDistanceTo(target)), this.phase);
		}
	}

	/** 形态进存档：重载后不能丢掉 Boss 形态，也不能把「已退回普通形态」读成未变身。 */
	@Override
	public void writeCustomDataToNbt(NbtCompound nbt) {
		super.writeCustomDataToNbt(nbt);
		nbt.putBoolean("BossForm", this.bossForm);
		nbt.putBoolean("BossFormDormant", this.bossFormDormant);
	}

	@Override
	public void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
		this.bossForm = nbt.getBoolean("BossForm");
		this.bossFormDormant = nbt.getBoolean("BossFormDormant");
		this.noTargetTicks = 0;
		this.previousHealthRatio = this.getHealth() / this.getMaxHealth();
		// 认回读档前挂上的脱战恢复 I，满血/挨打时才能清掉
		StatusEffectInstance regen = this.getStatusEffect(StatusEffects.REGENERATION);
		this.outOfCombatRegen = regen != null && regen.getAmplifier() == CombatProfile.OUT_OF_COMBAT_REGEN_AMPLIFIER
				&& regen.getDuration() == StatusEffectInstance.INFINITE;
		// 速度等级由 updateMovementSpeed 每 tick 按状态重算，读档后置空等它接管
		this.speedLevel = -1;
		if (this.bossForm && !this.getWorld().isClient()) {
			// 属性基值随 Attributes 存档；体型、阶段与增益属于状态，读档时补回来
			this.setBaseAttribute(EntityAttributes.GENERIC_SCALE, CombatProfile.BOSS_SCALE);
			this.phase = Math.max(2, this.combatPhaseFor(this.previousHealthRatio));
			this.updateFormAttributes();
			this.updatePhaseBuffs();
		}
	}

	// ------------------------------------------------------------------
	// Boss 血条、经验
	// ------------------------------------------------------------------

	/** 玩家开始追踪本实体时把血条加给他；离开范围或维度时摘掉（与凋灵同一套写法）。 */
	@Override
	public void onStartedTrackingBy(ServerPlayerEntity player) {
		super.onStartedTrackingBy(player);
		this.bossBar.track(player);
	}

	@Override
	public void onStoppedTrackingBy(ServerPlayerEntity player) {
		super.onStoppedTrackingBy(player);
		this.bossBar.untrack(player);
	}

	/** 血条跟随血量，但只有 Boss 形态才显示；按间隔刷新，值没变就不发包。 */
	private void updateBossBar() {
		boolean visible = this.bossForm && this.isAlive() && !this.isRemoved();
		// 阶段 4 只改血条表现：紫色 +「（狂暴）」，不改攻速与伤害
		this.bossBar.update(visible, this.age, this.getHealth() / this.getMaxHealth(), this.phase >= 4,
				this.getDisplayName());
	}

	@Override
	public void onDeath(DamageSource source) {
		super.onDeath(source);
		// 死亡动画期间就收起血条，别在屏幕上留一条空血条
		if (!this.getWorld().isClient()) {
		this.bossBar.clear();
		}
	}

	/** Boss 经验：死亡掉落 {@link #BOSS_XP_REWARD} 点（原版由击杀者吸收）。 */
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
	 * 只把"原版振动没有记录"的音效当作补充：同一动作的音效和 GameEvent 一般同 tick，
	 * 已经走过振动通道的声音在 {@link #wasGameEventHeardRecently} 里被排除。
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
		dispatchSupplement(player);
	}

	/** 把一次"没有对应 GameEvent"的音效分发给 20 格内的所有姚永寿。 */
	private static void dispatchSupplement(PlayerEntity player) {
		if (player.bypassesSteppingEffects()) {
			return;
		}
		World world = player.getWorld();
		Vec3d from = player.getEyePos();
		Box area = player.getBoundingBox().expand(HEARING_RANGE);
		for (YaoYongshouEntity yao : world.getEntitiesByClass(YaoYongshouEntity.class, area,
				candidate -> candidate.squaredDistanceTo(player) <= HEARING_RANGE_SQUARED)) {
			if (yao.bossForm) {
				continue;
			}
			if (isHearingOccluded(world, from, yao.getEyePos())) {
				continue;
			}
			// 同一动作的音效和 GameEvent 一般同 tick 或相邻 tick；已被振动记录的声音不重复算
			if (yao.wasGameEventHeardRecently(player)) {
				continue;
			}
			yao.hearSupplement(player);
		}
	}

	/** 该玩家最近是否被本实体的振动监听器接收过 GameEvent。 */
	private boolean wasGameEventHeardRecently(PlayerEntity player) {
		return this.ledger.wasEventHeardRecently(player.getUuid(), this.getWorld().getTime(), SUPPLEMENT_PAIR_TICKS);
	}

	/** 音效先到、GameEvent 后到：把刚才那次音效补充的愤怒撤回，避免一次动作算两次。 */
	private void undoSupplementIfAny(PlayerEntity player, long now) {
		this.ledger.rollbackSupplement(player.getUuid(), now, SUPPLEMENT_PAIR_TICKS, ANGER_PER_LOUD_NOISE);
	}

	/** 振动通道：按事件类型分大/小噪音，并记录声源位置供好奇使用。 */
	private void hearVibrationFrom(PlayerEntity player, int kind, Vec3d pos) {
		if (this.bossForm) {
			return;
		}
		long now = this.getWorld().getTime();
		if (kind != NOISE_LOUD) {
			AngerLedger.Noise noise = kind == NOISE_WALK ? AngerLedger.Noise.WALK
					: (kind == NOISE_JUMP ? AngerLedger.Noise.JUMP : AngerLedger.Noise.HURT);
			if (!this.ledger.allowMinorNoise(noise, player.getUuid(), now, MINOR_NOISE_COOLDOWN_TICKS)) {
				return;
			}
		}
		this.addAnger(player, kind == NOISE_LOUD ? ANGER_PER_LOUD_NOISE : ANGER_PER_MINOR_NOISE);
		this.triggerCuriosity(pos, now);
		this.updateAngerBehavior(now);
	}

	/** 音效补充：只处理没有 GameEvent 的声音，按大噪音计。 */
	private void hearSupplement(PlayerEntity player) {
		if (this.bossForm) {
			return;
		}
		long now = this.getWorld().getTime();
		this.ledger.markSupplement(player.getUuid(), now);
		this.addAnger(player, ANGER_PER_LOUD_NOISE);
		this.triggerCuriosity(Vec3d.ofCenter(player.getBlockPos()), now);
		this.updateAngerBehavior(now);
	}

	/** 好奇：冷静时听到声音就看向声源 40 tick，冷却期内不重复触发。 */
	private void triggerCuriosity(Vec3d pos, long now) {
		if (this.bossForm || this.sonicMode || this.getTarget() != null) {
			return;
		}
		if (now < this.nextCuriousAllowed) {
			return;
		}
		this.curiousPos = pos;
		this.curiousUntil = now + CURIOUS_DURATION_TICKS;
		this.nextCuriousAllowed = now + CURIOUS_DURATION_TICKS;
	}

	private void addAnger(PlayerEntity player, int amount) {
		this.ledger.addAnger(player.getUuid(), amount);
	}

	private int getAnger(PlayerEntity player) {
		return this.ledger.anger(player.getUuid());
	}

	/** 当前愤怒最高的存活玩家；同分取最近。 */
	private PlayerEntity getPrimeSuspect() {
		PlayerEntity best = null;
		int bestAnger = 0;
		double bestDistance = Double.MAX_VALUE;
		for (UUID uuid : this.ledger.angeredPlayers()) {
			PlayerEntity player = this.getWorld().getPlayerByUuid(uuid);
			if (player == null || !player.isAlive() || player.isSpectator()) {
				continue;
			}
			int anger = this.ledger.anger(uuid);
			double distance = this.squaredDistanceTo(player);
			if (anger > bestAnger || (anger == bestAnger && distance < bestDistance)) {
				best = player;
				bestAnger = anger;
				bestDistance = distance;
			}
		}
		return best;
	}

	/** 每 20 tick 全体愤怒 -1；归零条目、过期记忆与离线玩家一起清理。 */
	private void decayAnger(long now) {
		if (!this.ledger.decayIfDue(now, ANGER_DECAY_INTERVAL_TICKS, MINOR_NOISE_COOLDOWN_TICKS)) {
			return;
		}
		// 玩家登出后愤怒不再有意义：保留条目会在长跑服务器上无限增长
		if (this.getWorld() instanceof ServerWorld serverWorld) {
			this.ledger.retainPlayers(uuid -> serverWorld.getPlayerByUuid(uuid) != null);
		}
	}

	/** 阶段 1 按最高愤怒决定：警觉吐口水、激怒放声波、冷静收手；Boss 阶段由 {@link #updateBossCombat} 接管。 */
	private void updateAngerBehavior(long now) {
		if (this.bossForm) {
			return;
		}
		PlayerEntity prime = this.getPrimeSuspect();
		if (prime == null) {
			if (this.sonicMode) {
				this.stopSonicMode();
			}
			if (!this.revengeMode) {
				this.setTarget(null);
			}
			return;
		}
		int anger = this.getAnger(prime);
		boolean inSonicRange = this.squaredDistanceTo(prime) <= MAX_CHASE_RANGE_SQUARED;
		if (this.sonicMode) {
			// 声波退出比进入低一档，避免刚开一炮就掉档
			if (anger >= ANGER_ANGRY_EXIT && inSonicRange) {
				if (!prime.getUuid().equals(this.sonicTarget)) {
					this.sonicTarget = prime.getUuid();
					this.sonicCycle.reset();
					this.setTarget(prime);
				}
				return;
			}
			this.stopSonicMode();
		}
		if (anger >= ANGER_ANGRY && inSonicRange) {
			this.enterSonicMode(prime, now);
		} else if (anger >= ANGER_AGITATED) {
			if (!this.revengeMode && this.getTarget() != prime && inSonicRange) {
				this.setTarget(prime);
			}
		} else {
			if (!this.revengeMode) {
				this.setTarget(null);
			}
		}
	}

	public boolean isCurious(long now) {
		return now < this.curiousUntil;
	}

	// ------------------------------------------------------------------
	// 只读状态查询：供 entity.goal 包的目标读取，不改变实体状态
	// ------------------------------------------------------------------

	/** 是否处于 Boss 形态。 */
	public boolean isBossForm() {
		return this.bossForm;
	}

	/** 是否处于声波模式（仅阶段 1）。 */
	public boolean isSonicMode() {
		return this.sonicMode;
	}

	/** 最近一次听到声音的位置；从未听到时为 null。 */
	public Vec3d getCuriousPos() {
		return this.curiousPos;
	}

	/**
	 * 复刻原版 {@code VibrationListener#isOccluded}：沿视线每格采样，
	 * 碰到羊毛等 {@link BlockTags#OCCLUDES_VIBRATION_SIGNALS} 方块就算被挡住。
	 * 音效补充没有 GameEvent 的遮挡检查，这里补上，保证两种声源同一套规则。
	 */
	private static boolean isHearingOccluded(World world, Vec3d from, Vec3d to) {
		Vec3d delta = to.subtract(from);
		double length = delta.length();
		if (length < 1.0E-4D) {
			return false;
		}
		Vec3d step = delta.multiply(1.0D / length);
		for (double traveled = 0.0D; traveled < length; traveled += 1.0D) {
			BlockPos pos = BlockPos.ofFloored(from.add(step.multiply(traveled)));
			if (world.getBlockState(pos).isIn(BlockTags.OCCLUDES_VIBRATION_SIGNALS)) {
				return true;
			}
		}
		return false;
	}

	/** 监守者式振动回调：只接受玩家发出的、原版监听标签内的 GameEvent。 */
	private final class YaoVibrationCallback implements Vibrations.Callback {
		private final PositionSource positionSource = new EntityPositionSource(YaoYongshouEntity.this,
				YaoYongshouEntity.this.getStandingEyeHeight());

		@Override
		public int getRange() {
			return HEARING_RANGE_BLOCKS;
		}

		@Override
		public PositionSource getPositionSource() {
			return this.positionSource;
		}

		@Override
		public TagKey<GameEvent> getTag() {
			return GameEventTags.WARDEN_CAN_LISTEN;
		}

		@Override
		public boolean accepts(ServerWorld world, BlockPos pos, RegistryEntry<GameEvent> event,
				GameEvent.Emitter emitter) {
			if (YaoYongshouEntity.this.isAiDisabled() || YaoYongshouEntity.this.isDead()
					|| YaoYongshouEntity.this.bossForm) {
				// Boss 阶段完全不听声音：连配对标记都不记，避免影响下一次普通形态
				return false;
			}
			if (!world.getWorldBorder().contains(pos)) {
				return false;
			}
			Entity source = emitter.sourceEntity();
			// 只认玩家：怪物、爆炸、发射器发出的振动不参与索敌
			if (!(source instanceof PlayerEntity player)) {
				return false;
			}
			if (player.isSpectator() || !player.isAlive()) {
				return false;
			}
			// 此刻只是"派发"，accept 会按距离延迟；音效补充靠这个同 tick 标记配对
			YaoYongshouEntity.this.undoSupplementIfAny(player, world.getTime());
			YaoYongshouEntity.this.ledger.markEventHeard(player.getUuid(), world.getTime());
			return true;
		}

		@Override
		public void accept(ServerWorld world, BlockPos pos, RegistryEntry<GameEvent> event, Entity entity,
				Entity owner, float distance) {
			if (YaoYongshouEntity.this.isDead() || YaoYongshouEntity.this.isRemoved()) {
				return;
			}
			if (!(entity instanceof PlayerEntity player)) {
				return;
			}
			if (player.isSpectator() || !player.isAlive()) {
				return;
			}
			if (event.matches(GameEvent.ENTITY_DIE)) {
				return;
			}
			int kind;
			if (event.matches(GameEvent.STEP)) {
				kind = NOISE_WALK;
			} else if (event.matches(GameEvent.HIT_GROUND)) {
				kind = NOISE_JUMP;
			} else if (event.matches(GameEvent.ENTITY_DAMAGE)) {
				kind = NOISE_HURT;
			} else {
				kind = NOISE_LOUD;
			}
			YaoYongshouEntity.this.hearVibrationFrom(player, kind, Vec3d.ofCenter(pos));
		}
	}

	@Override
	public void tick() {
		// 振动的"距离延迟/选择"由原版 Ticker 推进，必须在服务端每 tick 调一次
		if (this.getWorld() instanceof ServerWorld serverWorld) {
			Vibrations.Ticker.tick(serverWorld, this.vibrationListenerData, this.vibrationCallback);
		}
		super.tick();
		World world = this.getWorld();
		if (world.isClient()) {
			return;
		}
		this.updateFormState();
		this.updateBossBar();
		long now = world.getTime();
		this.decayAnger(now);
		if (this.bossForm) {
			// Boss 阶段完全不听声音，硬锁当前/最近玩家
			this.updateBossCombat(now);
		} else {
			// 目标死亡/被移除就立刻收手，别对着尸体重启战斗
			LivingEntity currentTarget = this.getTarget();
			if (currentTarget != null && !currentTarget.isAlive()) {
				this.setTarget(null);
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
			this.updateAngerBehavior(now);
			if (this.sonicMode) {
				this.updateSonicMode(now);
			}
			LivingEntity chaseTarget = this.getTarget();
			if (chaseTarget instanceof PlayerEntity player
					&& this.squaredDistanceTo(player) > MAX_CHASE_RANGE_SQUARED) {
				// 阶段 1 目标跑出 24 格就放弃
				this.setTarget(null);
			}
			this.updateIdleFollow(now);
		}
		this.updateBossFormRevert();
		this.updateOutOfCombatRegen(now);
		this.updateMovementSpeed();
		EntityParticles.speedTrail(this);
		// 非 Boss 且不在战斗中（无目标）被注视：取消寻路，原地不动；战斗时不生效
		if (!this.bossForm && this.getTarget() == null && this.isWatchedByPlayer()) {
			this.getNavigation().stop();
			if (now - this.lastGazeLogTick >= 20L) {
				this.lastGazeLogTick = now;
				this.debug("GAZE freeze");
			}
		}
		this.updateCuriosityLook(now);
	}

	/** 好奇：冷静时把视线转向声源；玩家注视由 {@code CalmLookAtEntityGoal} 让位。 */
	private void updateCuriosityLook(long now) {
		if (!this.isCurious(now) || this.bossForm || this.sonicMode || this.getTarget() != null
				|| this.curiousPos == null) {
			return;
		}
		this.getLookControl().lookAt(this.curiousPos.x, this.curiousPos.y, this.curiousPos.z);
	}

	// ------------------------------------------------------------------
	// 监守者声波：5 秒内发声超过 8 次时触发，与吐口水互斥
	// ------------------------------------------------------------------

	/** 激怒：进入声波模式，目标跑出 24 格、死亡或愤怒回落时退出。 */
	private void enterSonicMode(PlayerEntity player, long now) {
		this.sonicMode = true;
		this.sonicCycle.reset();
		this.sonicTarget = player.getUuid();
		this.revengeMode = false;
		this.revengeShots = 0;
		debug("SONIC enter anger={}", this.getAnger(player));
		// 这里会立即转向；shootAt 在 17~24 格不吐口水，声波由 updateSonicMode 接管
		this.setTarget(player);
	}

	/** 退出声波模式但不立即改目标：愤怒回落到警觉时仍要继续吐口水。 */
	private void stopSonicMode() {
		if (!this.sonicMode) {
			return;
		}
		this.sonicMode = false;
		this.sonicCycle.reset();
		this.sonicTarget = null;
		debug("SONIC exit");
	}

	/** 阶段 1 声波：蓄力 34 tick → 开炮 → 循环 160 tick，只在 17~24 格生效。 */
	private void updateSonicMode(long now) {
		// 直接按 UUID 找锁定目标：中途被别的怪抢仇恨也不会退出声波模式
		PlayerEntity player = this.sonicTarget == null ? null : this.getWorld().getPlayerByUuid(this.sonicTarget);
		if (player == null || !player.isAlive() || this.squaredDistanceTo(player) > MAX_CHASE_RANGE_SQUARED) {
			this.stopSonicMode();
			this.setTarget(null);
			return;
		}
		if (this.getAnger(player) < ANGER_ANGRY_EXIT) {
			// 愤怒掉了：回到警觉口水，不继续开炮
			this.stopSonicMode();
			return;
		}
		if (this.getTarget() != player) {
			this.setTarget(player);
		}
		if (this.sonicCycle.isIdle()) {
			this.playSound(ModSounds.SONIC_CHARGE, 3.0F, 1.0F);
		}
		this.faceTarget(player);
		boolean charged = this.sonicCycle.tick();
		// 17~24 格才开炮；6~16 格由口水接管（shootAt 会在 17~24 格拒绝口水）
		double minRange = CombatProfile.sonicMinRange(1);
		double maxRange = CombatProfile.sonicMaxRange(1);
		if (charged
				&& this.squaredDistanceTo(player) >= minRange * minRange
				&& this.squaredDistanceTo(player) <= maxRange * maxRange) {
			this.fireSonicBoom(player, this.sonicDamage());
		}
	}

	/** 原版监守者声波：胸口的粒子束 + 按阶段伤害（全额无视护甲）+ 击退，只结算给锁定目标。 */
	private void fireSonicBoom(PlayerEntity target, float damage) {
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
		if (target.damage(world.getDamageSources().sonicBoom(this), damage)) {
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
		// 好奇：冷静时只转头看声源，优先于看人/张望
		this.goalSelector.add(1, new CuriousLookGoal(this));
		// 近战放优先级 2：只在 5 格内接管（canStart/shouldContinue 都卡距离），
		// 被打时能贴脸还手；目标一退出去就立刻把控制权还给远程
		this.goalSelector.add(2, new CloseMeleeGoal(this, 1.2, true));
		// 远程：阶段 1 由原版 Goal 控制走位/口水；Boss 阶段的走位/攻击由 updateBossCombat 接管
		this.goalSelector.add(3, new LockAwareProjectileAttackGoal(this, 1.25D, CombatProfile.SPIT_COOLDOWN_TICKS,
				(float) CombatProfile.spitRange(1)));
		this.goalSelector.add(6, new IdleWanderGoal(this, 1.0D));
		this.goalSelector.add(7, new CalmLookAtEntityGoal(this));
		this.goalSelector.add(8, new CalmLookAroundGoal(this));
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
		// 第一次锁定的瞬间就先打一枪：看得见、在射程内且冷却已好，就不等原版的开火倒计时
		double spitRange = CombatProfile.spitRange(this.phase);
		long now = this.getWorld().getTime();
		if (now >= this.nextSpitTick && !this.isSonicCharging() && this.squaredDistanceTo(target) <= spitRange * spitRange
				&& this.getVisibilityCache().canSee(target)) {
			this.shootAt(target, 1.0F);
			// 记一次冷却，避免下一 tick 又补一口
			this.nextSpitTick = now + CombatProfile.SPIT_COOLDOWN_TICKS;
		}
	}

	@Override
	public boolean damage(DamageSource source, float amount) {
		boolean damaged = super.damage(source, amount);
		if (!damaged) {
			return false;
		}
		debug("DAMAGE by {} rev={}/{}", source.getAttacker(), this.revengeMode, this.revengeShots);
		// 被谁打就锁定谁（创造模式玩家也照样反击）；同类造成的伤害不还手
		Entity attacker = source.getAttacker();
		if (!(attacker instanceof LivingEntity living) || living == this
				|| living instanceof YaoYongshouEntity) {
			return damaged;
		}
		if (living instanceof PlayerEntity player) {
			// 脱战回血只看玩家造成的伤害
			this.lastPlayerDamageTick = this.getWorld().getTime();
			if (this.bossForm) {
				// Boss 阶段硬锁：阶段 3/4 优先咬打它的玩家，阶段 2 只保持当前/最近玩家
				if (this.phase >= 3) {
					this.lockTarget = player.getUuid();
					this.setTarget(player);
				}
				return damaged;
			}
			this.addAnger(player, ANGER_PER_LOUD_NOISE);
		} else if (this.bossForm) {
			// Boss 阶段被其他生物打：目标不变，继续咬玩家
			return damaged;
		}
		if (this.sonicMode) {
			if (living == this.getTarget()) {
				// 打人的还是声波锁着的那位：保持锁定，近战由 CloseMeleeGoal 接管
				return damaged;
			}
			if (living instanceof PlayerEntity player) {
				// 换了个玩家贴脸打：声波跟着换目标，不中断
				this.ledger.setAnger(player.getUuid(), ANGER_ANGRY);
				this.sonicTarget = player.getUuid();
				this.sonicCycle.reset();
				this.setTarget(living);
				return damaged;
			}
			// 非玩家打进来：声波模式结束，回到普通反击
			this.stopSonicMode();
		}
		// 挨打引发的仇恨交给原版反击逻辑处理，不再受"安静就停火"的约束；
		// 但反击有上限：每次挨打最多还手 3 次，打完就收手
		this.revengeMode = true;
		this.revengeShots = CombatProfile.REVENGE_SHOT_LIMIT;
		this.revengeDeadline = this.getWorld().getTime() + CombatProfile.REVENGE_TIMEOUT_TICKS;
		this.setTarget(living);
		return damaged;
	}

	@Override
	public void shootAt(LivingEntity target, float pullProgress) {
		if (this.getWorld().isClient()) {
			return;
		}
		double distanceSquared = this.squaredDistanceTo(target);
		// 近距离只准近战
		if (distanceSquared <= this.meleeRangeSquared()) {
			return;
		}
		// 阶段 1 的 17~24 格由声波接管，不吐口水；6~16 格照常吐
		double sonicMin = CombatProfile.sonicMinRange(1);
		if (!this.bossForm && this.sonicMode && distanceSquared >= sonicMin * sonicMin) {
			return;
		}
		this.shootSpit(target, this.spitDamage(), this.spitPiercing(), this.spitKnockback());
		this.countRevengeShot();
	}

	/** 发射一发口水：伤害、穿甲与击退由调用方按阶段给出。 */
	private void shootSpit(LivingEntity target, float damage, float piercing, double knockback) {
		World world = this.getWorld();
		if (world.isClient()) {
			return;
		}
		debug("SHOOT dist={} dmg={} pierce={} rev={}/{}", (int) this.squaredDistanceTo(target), damage, piercing,
				this.revengeMode, this.revengeShots);
		double dx = target.getX() - this.getX();
		double dz = target.getZ() - this.getZ();
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		double aimY = target.getBodyY(0.4);

		// 开火前先把身体和头都转向玩家
		this.faceTarget(target);

		YaoYongshouSpitEntity spit = new YaoYongshouSpitEntity(ModEntities.YAO_SPIT, world);
		spit.setOwner(this);
		spit.setAttack(damage, piercing, knockback);
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
	}

	/** 反击计数：每次打出去减 1，用完就清目标收手。 */
	private void countRevengeShot() {
		if (!this.revengeMode) {
			return;
		}
		this.revengeDeadline = this.getWorld().getTime() + CombatProfile.REVENGE_TIMEOUT_TICKS;
		if (--this.revengeShots <= 0) {
			this.revengeMode = false;
			// 连"上次攻击者"一起忘掉，避免任何原版逻辑把它重新锁回来
			this.setAttacker(null);
			this.setTarget(null);
		}
	}

	/**
	 * 近战：伤害走自定义类型 {@link #MELEE_DAMAGE_TYPE}（`scaling: never`），和平难度也生效；
	 * 阶段 3/4 有部分伤害经 {@link YaoArmorPiercingDamageSource} 无视护甲。
	 * 挥击动画与脚下扬尘按"挥击发生"触发，不依赖伤害是否结算成功。
	 */
	@Override
	public boolean tryAttack(Entity target) {
		boolean hit;
		if (target instanceof LivingEntity livingTarget) {
			RegistryEntry<DamageType> type = this.getWorld().getRegistryManager()
					.get(RegistryKeys.DAMAGE_TYPE).entryOf(MELEE_DAMAGE_TYPE);
			DamageSource source = new YaoArmorPiercingDamageSource(type, this, this, this.meleePiercing());
			hit = livingTarget.damage(source, this.meleeDamage());
		} else {
			hit = super.tryAttack(target);
		}
		// 命中瞬间记录 age：客户端按渲染插值算进度，动画从这一 tick 平滑开始
		this.dataTracker.set(ATTACK_START_AGE, this.age);
		// 命中点的扬尘：dust_pillar 贴在目标脚下，比打在脸上更像"砸出来的"
		EntityParticles.meleeDust(this, target);
		if (hit && target instanceof LivingEntity livingTarget) {
			// 命中后向上顶 0.4 × (1 - 击退抗性)，水平方向不额外加
			double resistance = Math.max(0.0D,
					1.0D - livingTarget.getAttributeValue(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE));
			livingTarget.setVelocity(livingTarget.getVelocity().add(0.0D, 0.4D * resistance, 0.0D));
		}
		if (!hit) {
			// 创造模式等免疫目标会走到这里；和平难度已由自定义伤害类型解决
			debug("MELEE miss type={} creative={} phase={}", target.getType(),
					target instanceof PlayerEntity player && player.isCreative(), this.phase);
		} else {
			debug("MELEE hit type={} dist2={} phase={}", target.getType(),
					(int) this.squaredDistanceTo(target), this.phase);
		}
		if (hit) {
			this.countRevengeShot();
		}
		return hit;
	}
}
