package com.example.hush.entity.goal;

import com.example.hush.entity.YaoYongshouEntity;

import net.minecraft.entity.ai.goal.WanderAroundFarGoal;

/** 玩家在 16~20 格的"合适距离"内就站定只看人：把原版游走拦掉。 */
public class IdleWanderGoal extends WanderAroundFarGoal {
	private final YaoYongshouEntity yao;

	public IdleWanderGoal(YaoYongshouEntity yao, double speed) {
		super(yao, speed);
		this.yao = yao;
	}

	@Override
	public boolean canStart() {
		return !this.yao.shouldStandStill() && super.canStart();
	}

	@Override
	public boolean shouldContinue() {
		return !this.yao.shouldStandStill() && super.shouldContinue();
	}
}
