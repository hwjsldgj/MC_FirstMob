package com.example.hush.entity.goal;

import com.example.hush.entity.YaoYongshouEntity;

import net.minecraft.entity.ai.goal.LookAroundGoal;

/** 随机张望：好奇期间让位给声源方向。 */
public class CalmLookAroundGoal extends LookAroundGoal {
	private final YaoYongshouEntity yao;

	public CalmLookAroundGoal(YaoYongshouEntity yao) {
		super(yao);
		this.yao = yao;
	}

	@Override
	public boolean canStart() {
		return !this.yao.isCurious(this.yao.getWorld().getTime()) && super.canStart();
	}

	@Override
	public boolean shouldContinue() {
		return !this.yao.isCurious(this.yao.getWorld().getTime()) && super.shouldContinue();
	}
}
