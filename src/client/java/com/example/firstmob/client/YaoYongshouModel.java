package com.example.firstmob.client;

import com.example.firstmob.entity.YaoYongshouEntity;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.CrossbowPosing;
import net.minecraft.util.math.MathHelper;

/**
 * 僵尸外形：沿用 64×64 的人形骨架（{@code EntityModelLayers.ZOMBIE}）。
 * <p>
 * 待机时手臂复用原版 {@link CrossbowPosing#meleeAttack}（僵尸双臂前伸）；
 * 攻击时按实体同步过来的 {@code ATTACK_ANIM} 计时播一段自定义抡臂——抬到头顶再砸下，
 * 比原版僵尸 6 tick 的微幅摆动更容易看清，也更接近铁傀儡的双臂砸地。
 * <p>
 * 原版 {@code ZombieEntityModel} / {@code IronGolemEntityModel} 的泛型都绑死在
 * {@code ZombieEntity} / {@code IronGolemEntity} 上，无法直接套到本实体，所以这里只借骨架与姿势函数。
 */
public class YaoYongshouModel extends BipedEntityModel<YaoYongshouEntity> {
	/** 待机手臂俯仰（-80°）：原版僵尸双臂前伸。 */
	private static final float IDLE_ARM_PITCH = -1.3962634F;
	/** 抬到头顶（-138°）。 */
	private static final float RAISED_ARM_PITCH = -2.4085544F;
	/** 抡到底（-20°）。 */
	private static final float SLAM_ARM_PITCH = -0.3490658F;
	/** 0~0.35 抬臂，0.35~0.7 抡下，0.7~1 回位。 */
	private static final float RAISE_PORTION = 0.35F;
	private static final float SLAM_PORTION = 0.7F;

	public YaoYongshouModel(ModelPart root) {
		super(root);
	}

	@Override
	public void setAngles(YaoYongshouEntity entity, float limbAngle, float limbDistance, float animationProgress,
			float headYaw, float headPitch) {
		super.setAngles(entity, limbAngle, limbDistance, animationProgress, headYaw, headPitch);
		float swing = entity.getAttackAnimationProgress();
		if (swing <= 0.0F) {
			// 待机：僵尸双臂前伸，走路时带一点原版摆动
			CrossbowPosing.meleeAttack(this.leftArm, this.rightArm, false, this.handSwingProgress, animationProgress);
			return;
		}
		this.poseSlam(swing);
	}

	/** 三段线性插值的抡臂：抬起 → 砸下 → 回到待机姿势。 */
	private void poseSlam(float swing) {
		float pitch;
		if (swing < RAISE_PORTION) {
			pitch = MathHelper.lerp(swing / RAISE_PORTION, IDLE_ARM_PITCH, RAISED_ARM_PITCH);
		} else if (swing < SLAM_PORTION) {
			pitch = MathHelper.lerp((swing - RAISE_PORTION) / (SLAM_PORTION - RAISE_PORTION),
					RAISED_ARM_PITCH, SLAM_ARM_PITCH);
		} else {
			pitch = MathHelper.lerp((swing - SLAM_PORTION) / (1.0F - SLAM_PORTION),
					SLAM_ARM_PITCH, IDLE_ARM_PITCH);
		}
		this.rightArm.pitch = pitch;
		this.leftArm.pitch = pitch;
		this.rightArm.yaw = 0.0F;
		this.leftArm.yaw = 0.0F;
		this.rightArm.roll = 0.0F;
		this.leftArm.roll = 0.0F;
	}
}
