package com.example.hush.entity.goal;

import com.example.hush.entity.YaoYongshouEntity;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.mob.PathAwareEntity;

/** 只在贴身距离才触发的近战：优先级 2，进出都由距离把关，不会压制远程吐口水。 */
public class CloseMeleeGoal extends MeleeAttackGoal {
	private final YaoYongshouEntity yao;

	public CloseMeleeGoal(YaoYongshouEntity yao, double speed, boolean pauseWhenMobIdle) {
		super((PathAwareEntity) yao, speed, pauseWhenMobIdle);
		this.yao = yao;
	}

	private boolean targetInMeleeRange() {
		LivingEntity target = this.yao.getTarget();
		// 所有阶段都是 5 格近战
		return target != null && this.yao.squaredDistanceTo(target) <= this.yao.meleeRangeSquared();
	}

	@Override
	public boolean canStart() {
		// 声波蓄力中不抢控制权：先把这一发打完
		return !this.yao.isSonicCharging() && this.targetInMeleeRange() && super.canStart();
	}

	@Override
	public boolean shouldContinue() {
		// 目标一退出 5 格马上撒手，把控制权还给远程
		return !this.yao.isSonicCharging() && this.targetInMeleeRange() && super.shouldContinue();
	}
}
