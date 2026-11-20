package com.example.hush.entity.goal;

import com.example.hush.entity.YaoYongshouEntity;

import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.player.PlayerEntity;

/** 玩家注视：好奇看声源期间让位，其余时间沿用原版行为。 */
public class CalmLookAtEntityGoal extends LookAtEntityGoal {
	private final YaoYongshouEntity yao;

	public CalmLookAtEntityGoal(YaoYongshouEntity yao) {
		super(yao, PlayerEntity.class, 8.0F);
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
