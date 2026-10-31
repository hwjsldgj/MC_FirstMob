package com.example.firstmob.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.passive.LlamaEntity;
import net.minecraft.world.World;

/**
 * 姚永寿：只保留村民的外形（由渲染器负责），
 * 行为完全改用羊驼：寻路、跟随商队、远程吐口水；近距离额外加近战。
 */
public class YaoYongshouEntity extends LlamaEntity {
	public YaoYongshouEntity(EntityType<? extends LlamaEntity> entityType, World world) {
		super(entityType, world);
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
