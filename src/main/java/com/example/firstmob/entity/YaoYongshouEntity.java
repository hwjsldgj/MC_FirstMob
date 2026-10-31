package com.example.firstmob.entity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.passive.LlamaEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
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
	/** 同一玩家在窗口内发声达到这个次数就主动开火（即"大于 2 次"）。 */
	private static final int SOUND_THRESHOLD = 3;
	/** 窗口内发声次数低于这个值就停火（即"小于 2 次"）。 */
	private static final int RELEASE_SOUND_COUNT = 2;
	/** "至少攻击一次"的保证：和计数窗口一样只给 5 秒，安静下来就不再死追。 */
	private static final int GUARANTEE_TIMEOUT_TICKS = 100;

	/** 每个玩家最近几次发声的游戏时间，超过窗口的会自动丢弃。 */
	private final Map<UUID, Deque<Long>> recentSounds = new HashMap<>();
	/** 当前由"吵闹"触发攻击的目标；null 表示不是声音触发的攻击。 */
	private UUID noiseTarget;
	/** 本次触发还欠几次攻击（至少 1 次）。 */
	private int guaranteedShots;
	/** 本轮触发的时间，用于保证期超时。 */
	private long noiseTriggerTime;

	public YaoYongshouEntity(EntityType<? extends LlamaEntity> entityType, World world) {
		super(entityType, world);
	}

	/**
	 * 玩家每发出一次音效就会调用这里（见 {@code PlayerSoundMixin}）。
	 * 找出 20 格内的所有姚永寿，分别记一次账。
	 */
	public static void onPlayerSound(PlayerEntity player, SoundEvent sound) {
		World world = player.getWorld();
		if (world.isClient() || !player.isAlive() || player.isSpectator() || isExcludedSound(sound)) {
			return;
		}
		Box area = player.getBoundingBox().expand(HEARING_RANGE);
		for (YaoYongshouEntity yao : world.getEntitiesByClass(YaoYongshouEntity.class, area,
				candidate -> candidate.squaredDistanceTo(player) <= HEARING_RANGE_SQUARED)) {
			yao.hearSoundFrom(player);
		}
	}

	/**
	 * 不参与计数的音效：
	 * <ul>
	 *     <li>脚步：走路不算主动吵闹，否则玩家一边走就一边把仇恨续着</li>
	 *     <li>受伤/死亡：被自己打出来的声音会把仇恨自己续上，形成死循环</li>
	 * </ul>
	 */
	private static boolean isExcludedSound(SoundEvent sound) {
		Identifier id = sound.getId();
		if (id == null) {
			return false;
		}
		String path = id.getPath();
		return path.endsWith(".step") || path.endsWith(".hurt") || path.endsWith(".death");
	}

	/** 记账：5 秒内同一玩家吵闹 3 次以上就主动锁定他，并保证至少吐一次口水。 */
	private void hearSoundFrom(PlayerEntity player) {
		long now = this.getWorld().getTime();
		Deque<Long> timestamps = this.recentSounds.computeIfAbsent(player.getUuid(), uuid -> new ArrayDeque<>());
		timestamps.addLast(now);
		while (!timestamps.isEmpty() && now - timestamps.peekFirst() > SOUND_WINDOW_TICKS) {
			timestamps.removeFirst();
		}
		if (timestamps.size() >= SOUND_THRESHOLD && !player.getUuid().equals(this.noiseTarget)) {
			// 新的一轮触发：锁定目标 + 记下"至少攻击一次"的欠账
			this.noiseTarget = player.getUuid();
			this.guaranteedShots = 1;
			this.noiseTriggerTime = now;
			// 锁定后交给羊驼自带的 ProjectileAttackGoal：够近就吐口水，太远就先走近
			this.setTarget(player);
		}
	}

	/** 统计并清理某玩家在窗口内的发声次数。 */
	private int countSoundsInWindow(UUID uuid, long now) {
		Deque<Long> timestamps = this.recentSounds.get(uuid);
		if (timestamps == null) {
			return 0;
		}
		while (!timestamps.isEmpty() && now - timestamps.peekFirst() > SOUND_WINDOW_TICKS) {
			timestamps.removeFirst();
		}
		if (timestamps.isEmpty()) {
			this.recentSounds.remove(uuid);
			return 0;
		}
		return timestamps.size();
	}

	@Override
	public void tick() {
		super.tick();
		World world = this.getWorld();
		if (world.isClient()) {
			return;
		}
		long now = world.getTime();
		// 目标跑出 25 格：直接放弃追击（不再寻路、不再吐口水）
		LivingEntity chaseTarget = this.getTarget();
		if (chaseTarget instanceof PlayerEntity player && this.squaredDistanceTo(player) > MAX_CHASE_RANGE_SQUARED) {
			this.setTarget(null);
			this.noiseTarget = null;
			this.guaranteedShots = 0;
		}
		this.updateNoiseAggro(now);
		if (now % 100L == 0L && !this.recentSounds.isEmpty()) {
			// 玩家走远/不再发声后清掉记录，避免长期占内存
			this.recentSounds.keySet().removeIf(uuid -> {
				Deque<Long> stamps = this.recentSounds.get(uuid);
				return stamps == null || stamps.isEmpty() || now - stamps.peekLast() > SOUND_WINDOW_TICKS;
			});
		}
	}

	/** 声音触发的攻击：安静下来就停火，但触发后至少要把那一次攻击打完。 */
	private void updateNoiseAggro(long now) {
		if (this.noiseTarget == null) {
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
		if (!guaranteed && this.countSoundsInWindow(this.noiseTarget, now) < RELEASE_SOUND_COUNT) {
			// 窗口内已经吵不到 2 次了：停止攻击
			this.setTarget(null);
			this.noiseTarget = null;
		}
	}

	@Override
	protected void initGoals() {
		// 羊驼原生 AI：游泳/绑定/商队/吐口水(ProjectileAttackGoal)/逃跑/繁殖/诱惑/闲逛/注视
		super.initGoals();
		// 远程优先：羊驼的 ProjectileAttackGoal(优先级 3) 会自己"太远就走近、进入射程就停下吐口水"。
		// 近战放到优先级 4，只在远程目标丢失/不可见时才补位，不会抢远程的优先级。
		this.goalSelector.add(4, new CloseMeleeGoal(this, 1.2, true));
	}

	/** 同类不互殴：任何目标选择器想把同类当目标都会被拦下。 */
	@Override
	public void setTarget(LivingEntity target) {
		if (target instanceof YaoYongshouEntity) {
			return;
		}
		super.setTarget(target);
	}

	@Override
	public boolean damage(DamageSource source, float amount) {
		boolean damaged = super.damage(source, amount);
		if (damaged) {
			// 被谁打就锁定谁（创造模式玩家也照样反击）；同类造成的伤害不还手
			Entity attacker = source.getAttacker();
			if (attacker instanceof LivingEntity living && living != this && !(living instanceof YaoYongshouEntity)) {
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
		if (world.isClient()) {
			return;
		}
		double dx = target.getX() - this.getX();
		double dz = target.getZ() - this.getZ();
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		double aimY = target.getBodyY(0.4);

		// 开火前先把身体和头都转向玩家
		float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
		this.setYaw(yaw);
		this.bodyYaw = yaw;
		this.headYaw = yaw;
		this.setPitch((float) (-(Math.atan2(aimY - this.getEyeY(), horizontal) * 180.0 / Math.PI)));

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

	/** 只在贴身距离才触发的近战，避免一直压制远程吐口水。 */
	private static class CloseMeleeGoal extends MeleeAttackGoal {
		CloseMeleeGoal(PathAwareEntity mob, double speed, boolean pauseWhenMobIdle) {
			super(mob, speed, pauseWhenMobIdle);
		}

		@Override
		public boolean canStart() {
			LivingEntity target = this.mob.getTarget();
			if (target == null || this.mob.squaredDistanceTo(target) > 9.0) {
				return false;
			}
			return super.canStart();
		}
	}
}
