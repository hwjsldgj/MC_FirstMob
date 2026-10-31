package com.example.firstmob.entity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.ProjectileAttackGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.LlamaEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * 姚永寿：只保留村民的外形（由渲染器负责），
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
	/** "至少攻击一次"的保证：和计数窗口一样只给 5 秒，安静下来就不再死追。 */
	private static final int GUARANTEE_TIMEOUT_TICKS = 100;
	/** 停火后，纯脚步声要等这么久才能再次触发（避免走两步就重新开打）。 */
	private static final int STEP_RETRIGGER_COOLDOWN = 100;
	/** 吐口水冷却：羊驼默认 40 tick，这里减 0.3 秒 = 34 tick。 */
	private static final int SPIT_COOLDOWN_TICKS = 34;
	/** 吐口水最大射程（格），与羊驼默认一致。 */
	private static final double SPIT_RANGE = 20.0D;

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

	/** 每个玩家的全部发声时间（含脚步），用于判断"吵不吵"。 */
	private final Map<UUID, Deque<Long>> recentSounds = new HashMap<>();
	/** 每个玩家的"非脚步"发声时间，用于判断还要不要继续打。 */
	private final Map<UUID, Deque<Long>> recentLoudSounds = new HashMap<>();
	/** 每个玩家上一次脚步 / 真噪音 / 被姚永寿打中发声的 tick，用于同一 tick 内去重。 */
	private final Map<UUID, Long> lastStepTicks = new HashMap<>();
	private final Map<UUID, Long> lastLoudTicks = new HashMap<>();
	private final Map<UUID, Long> lastHitTicks = new HashMap<>();
	/** 当前由"吵闹"触发攻击的目标；null 表示不是声音触发的攻击。 */
	private UUID noiseTarget;
	/** 本次触发还欠几次攻击（至少 1 次）。 */
	private int guaranteedShots;
	/** 本轮触发的时间，用于保证期超时。 */
	private long noiseTriggerTime;
	/** 这个时间点之前，纯脚步声不再触发攻击。 */
	private long stepTriggerAllowedAt;
	/** 是否处于声波模式：为 true 时只放声波、不吐口水。 */
	private boolean sonicMode;
	/** 距离上一次声波已经过去的 tick 数，用来做蓄力/冷却节拍。 */
	private int sonicTicks;

	public YaoYongshouEntity(EntityType<? extends LlamaEntity> entityType, World world) {
		super(entityType, world);
	}

	/**
	 * 玩家每发出一次音效就会调用这里（见 {@code PlayerSoundMixin}）。
	 * 脚步/落地声、以及被姚永寿打出来的受伤音效只算"一次发声"，不维持仇恨。
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
		// 脚步、落地/跳跃声都算"移动声"：只触发，且与脚步共用同一份 5 秒限量
		boolean step = path.endsWith(".step") || path.endsWith(".small_fall") || path.endsWith(".big_fall");
		// 被姚永寿打出的受伤音效只记 1 次，而且不算"持续吵闹"（否则追打会自己续上，永远停不下来）
		boolean hitByYao = path.endsWith(".hurt") && isHurtByYao(player);
		dispatch(player, !step && !hitByYao, step);
	}

	/** 挖方块、与方块交互这些不走音效通道的动作，直接记一次"巨响"（见 {@code PlayerInteractionMixin}）。 */
	public static void onPlayerAction(PlayerEntity player) {
		World world = player.getWorld();
		if (world.isClient() || !player.isAlive() || player.isSpectator()) {
			return;
		}
		dispatch(player, true, false);
	}

	/** 把一次"声音"分发给 20 格内的所有姚永寿。 */
	private static void dispatch(PlayerEntity player, boolean loud, boolean step) {
		World world = player.getWorld();
		Box area = player.getBoundingBox().expand(HEARING_RANGE);
		for (YaoYongshouEntity yao : world.getEntitiesByClass(YaoYongshouEntity.class, area,
				candidate -> candidate.squaredDistanceTo(player) <= HEARING_RANGE_SQUARED)) {
			yao.hearSoundFrom(player, loud, step);
		}
	}

	private static boolean isHurtByYao(PlayerEntity player) {
		DamageSource source = player.getRecentDamageSource();
		if (source == null) {
			return false;
		}
		return source.isOf(YaoYongshouSpitEntity.DAMAGE_TYPE) || source.getAttacker() instanceof YaoYongshouEntity;
	}

	/** 记账：窗口内声音够多就触发；只有"真噪音"才维持仇恨。 */
	private void hearSoundFrom(PlayerEntity player, boolean loud, boolean step) {
		UUID uuid = player.getUuid();
		long now = this.getWorld().getTime();
		// 脚步声在同一个统计窗口（5 秒）里只算 1 次，走多久都不会把计数刷上去
		if (step) {
			Long lastStep = this.lastStepTicks.get(uuid);
			if (lastStep != null && now - lastStep < SOUND_WINDOW_TICKS) {
				return;
			}
			this.lastStepTicks.put(uuid, now);
		} else {
			// 同一次发声可能在同一 tick 触发好几条音效（比如被口水打中：受伤音效 + 其它），
			// 同一 tick、同一类只记 1 次，避免一次事件被算成好几次
			Map<UUID, Long> lastTicks = loud ? this.lastLoudTicks : this.lastHitTicks;
			Long last = lastTicks.get(uuid);
			if (last != null && last.longValue() == now) {
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
		if (countInWindow(this.recentSounds, uuid, now) < SOUND_THRESHOLD) {
			return;
		}
		if (step && now < this.stepTriggerAllowedAt) {
			// 刚停火不久，别被脚步声立刻叫回去
			return;
		}
		this.noiseTarget = uuid;
		this.guaranteedShots = 1;
		this.noiseTriggerTime = now;
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
		long now = world.getTime();
		if (this.sonicMode) {
			this.updateSonicMode(now);
		} else {
			// 目标跑出 25 格：直接放弃追击（声波模式另算，见 updateSonicMode）
			LivingEntity chaseTarget = this.getTarget();
			if (chaseTarget instanceof PlayerEntity player && this.squaredDistanceTo(player) > MAX_CHASE_RANGE_SQUARED) {
				this.setTarget(null);
				this.noiseTarget = null;
				this.guaranteedShots = 0;
			}
		}
		this.updateNoiseAggro(now);
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
		// 判断"吵不吵"只看非脚步声：光走路不算持续吵闹
		if (!guaranteed && countInWindow(this.recentLoudSounds, this.noiseTarget, now) < RELEASE_SOUND_COUNT) {
			this.setTarget(null);
			this.noiseTarget = null;
			this.stepTriggerAllowedAt = now + STEP_RETRIGGER_COOLDOWN;
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
		this.setTarget(null);
		this.noiseTarget = null;
		this.guaranteedShots = 0;
		this.stepTriggerAllowedAt = now + STEP_RETRIGGER_COOLDOWN;
	}

	/** 声波模式下每 tick 走一遍：蓄力 34 tick → 开炮 → 冷却到 160 tick 再来。 */
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
			this.playSound(SoundEvents.ENTITY_WARDEN_SONIC_CHARGE, 3.0F, 1.0F);
		}
		this.faceTarget(player);
		this.sonicTicks++;
		if (this.sonicTicks == SONIC_CHARGE_TICKS) {
			this.fireSonicBoom(player);
		}
		if (this.sonicTicks >= SONIC_INTERVAL_TICKS) {
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
		this.playSound(SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 3.0F, 1.0F);
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
		// 只保留目标选择器（被攻击后的反击），行为目标全部重建
		super.initGoals();
		// 羊驼的商队、绑定/骑乘、繁殖、诱惑、跟随父母等一概不要，只留普通寻路 + 吐口水
		this.goalSelector.clear(goal -> true);
		this.goalSelector.add(0, new SwimGoal(this));
		// 近战放优先级 2：只在 3 格内接管（canStart/shouldContinue 都卡距离），
		// 被打时能贴脸还手；目标一退出去就立刻把控制权还给远程
		this.goalSelector.add(2, new CloseMeleeGoal(this, 1.2, true));
		// 远程：太远就走近，进入射程就停下吐口水
		this.goalSelector.add(3, new ProjectileAttackGoal(this, 1.25D, SPIT_COOLDOWN_TICKS, (float) SPIT_RANGE));
		this.goalSelector.add(6, new WanderAroundFarGoal(this, 1.0D));
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
		if (!this.sonicMode && this.squaredDistanceTo(target) <= SPIT_RANGE * SPIT_RANGE
				&& this.getVisibilityCache().canSee(target)) {
			this.shootAt(target, 1.0F);
		}
	}

	@Override
	public boolean damage(DamageSource source, float amount) {
		boolean damaged = super.damage(source, amount);
		if (damaged) {
			// 被谁打就锁定谁（创造模式玩家也照样反击）；同类造成的伤害不还手
			Entity attacker = source.getAttacker();
			if (attacker instanceof LivingEntity living && living != this && !(living instanceof YaoYongshouEntity)) {
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
				// 挨打引发的仇恨交给原版反击逻辑处理，不再受"安静就停火"的约束
				this.noiseTarget = null;
				this.guaranteedShots = 0;
				this.setTarget(living);
			}
		}
		return damaged;
	}

	@Override
	public void shootAt(LivingEntity target, float pullProgress) {
		World world = this.getWorld();
		// 声波模式与吐口水互斥：同一时间只用一种远程手段
		if (world.isClient() || this.sonicMode) {
			return;
		}
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
				net.minecraft.sound.SoundEvents.ENTITY_LLAMA_SPIT, net.minecraft.sound.SoundCategory.NEUTRAL, 1.0F, 1.0F);
		world.spawnEntity(spit);

		// 本轮"至少攻击一次"已兑现
		if (this.guaranteedShots > 0 && this.getTarget() instanceof PlayerEntity player
				&& player.getUuid().equals(this.noiseTarget)) {
			this.guaranteedShots--;
		}
	}

	/** 近战命中同样算"已经攻击过一次"。 */
	@Override
	public boolean tryAttack(Entity target) {
		boolean hit = super.tryAttack(target);
		if (hit && this.guaranteedShots > 0 && target instanceof PlayerEntity player
				&& player.getUuid().equals(this.noiseTarget)) {
			this.guaranteedShots--;
		}
		return hit;
	}

	/** 只在贴身距离才触发的近战：优先级 2，但进出都由距离把关，不会压制远程吐口水。 */
	private static class CloseMeleeGoal extends MeleeAttackGoal {
		private static final double MELEE_RANGE_SQUARED = 9.0D;

		CloseMeleeGoal(PathAwareEntity mob, double speed, boolean pauseWhenMobIdle) {
			super(mob, speed, pauseWhenMobIdle);
		}

		private boolean targetInMeleeRange() {
			LivingEntity target = this.mob.getTarget();
			return target != null && this.mob.squaredDistanceTo(target) <= MELEE_RANGE_SQUARED;
		}

		@Override
		public boolean canStart() {
			// 3 格内才接管，避免在远处就跟远程抢控制权
			return this.targetInMeleeRange() && super.canStart();
		}

		@Override
		public boolean shouldContinue() {
			// 目标一退出 3 格马上撒手，把控制权还给 ProjectileAttackGoal
			return this.targetInMeleeRange() && super.shouldContinue();
		}
	}
}
