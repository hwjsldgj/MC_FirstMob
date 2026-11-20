package com.example.hush.entity.goal;

import com.example.hush.entity.YaoYongshouEntity;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.ProjectileAttackGoal;

/** Boss 阶段的走位/攻击由实体统一接管，原版远程 AI 只负责盯着目标。 */
public class LockAwareProjectileAttackGoal extends ProjectileAttackGoal {
	private final YaoYongshouEntity yao;

	public LockAwareProjectileAttackGoal(YaoYongshouEntity yao, double speed, int attackInterval, float range) {
		super(yao, speed, attackInterval, range);
		this.yao = yao;
	}

	@Override
	public void tick() {
		// Boss 阶段的走位与攻击由实体自己接管，只留视线
		if (!this.yao.isBossForm()) {
			super.tick();
			return;
		}
		LivingEntity target = this.yao.getTarget();
		if (target != null) {
			this.yao.getLookControl().lookAt(target, 30.0F, 30.0F);
		}
	}
}
