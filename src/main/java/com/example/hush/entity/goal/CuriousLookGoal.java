package com.example.hush.entity.goal;

import java.util.EnumSet;

import com.example.hush.entity.YaoYongshouEntity;

import net.minecraft.entity.ai.goal.Goal;

/** 好奇：冷静时只转头看声源；期间把玩家注视/随机张望让开。 */
public class CuriousLookGoal extends Goal {
	private final YaoYongshouEntity yao;

	public CuriousLookGoal(YaoYongshouEntity yao) {
		this.yao = yao;
		this.setControls(EnumSet.of(Goal.Control.LOOK));
	}

	@Override
	public boolean canStart() {
		return this.shouldLook();
	}

	@Override
	public boolean shouldContinue() {
		return this.shouldLook();
	}

	private boolean shouldLook() {
		return !this.yao.isBossForm() && !this.yao.isSonicMode() && this.yao.getTarget() == null
				&& this.yao.getCuriousPos() != null
				&& this.yao.isCurious(this.yao.getWorld().getTime());
	}

	@Override
	public void tick() {
		var pos = this.yao.getCuriousPos();
		this.yao.getLookControl().lookAt(pos.x, pos.y, pos.z);
	}
}
