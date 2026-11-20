package com.example.hush.visual;

import com.example.hush.entity.YaoYongshouEntity;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustColorTransitionParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.joml.Vector3f;

/**
 * 姚永寿的粒子表现：变身尘团、阶段 4 图腾、速度拖尾、近战扬尘。
 * <p>
 * 只读实体状态、只向 {@link ServerWorld} 发粒子，不回写任何游戏状态；
 * 客户端在 {@code tick()} 提前返回，所以这里统一判断服务端，避免重复生成。
 */
public final class EntityParticles {
	/** Boss 变身粒子：熔岩橙 → 暗紫的尘团，2 倍体型下也包得住全身。 */
	private static final int BOSS_FORM_COUNT = 80;
	private static final float BOSS_FORM_SCALE = 1.5F;
	private static final double BOSS_FORM_SPREAD = 1.2D;
	private static final Vector3f BOSS_FORM_FROM = new Vector3f(1.0F, 0.3F, 0.0F);
	private static final Vector3f BOSS_FORM_TO = new Vector3f(0.35F, 0.0F, 0.5F);
	/** 阶段 4 触发粒子：原版不死图腾同款，在身体中心炸开。 */
	private static final int PHASE_4_COUNT = 100;
	private static final double PHASE_4_HORIZONTAL_SPREAD = 0.6D;
	private static final double PHASE_4_VERTICAL_SPREAD = 1.0D;
	/** 速度拖尾：任何速度等级都撒烟花，每 4 tick 2 颗，避免 2 倍体型下刷屏。 */
	private static final int SPEED_TRAIL_INTERVAL_TICKS = 4;
	private static final int SPEED_TRAIL_COUNT = 2;
	/** "在走路"的判据：脚踩地面且水平速度 > 0.05 格/tick。 */
	private static final double SPEED_TRAIL_MIN_VELOCITY_SQUARED = 0.0025D;
	/** 近战命中扬尘：贴在目标脚下，贴图取脚下方块。 */
	private static final int MELEE_DUST_COUNT = 6;
	private static final double MELEE_DUST_SPREAD = 0.35D;

	private EntityParticles() {
	}

	/** 转 Boss 形态当 tick：熔岩橙 → 暗紫的尘团从身体中心炸开。 */
	public static void bossForm(YaoYongshouEntity yao) {
		if (!(yao.getWorld() instanceof ServerWorld world)) {
			return;
		}
		DustColorTransitionParticleEffect effect = new DustColorTransitionParticleEffect(
				BOSS_FORM_FROM, BOSS_FORM_TO, BOSS_FORM_SCALE);
		world.spawnParticles(effect, yao.getX(), yao.getBodyY(0.5D), yao.getZ(),
				BOSS_FORM_COUNT, BOSS_FORM_SPREAD, BOSS_FORM_SPREAD, BOSS_FORM_SPREAD, 0.05D);
	}

	/** 阶段 4 触发当 tick：不死图腾粒子在身体中心炸开。 */
	public static void phase4(YaoYongshouEntity yao) {
		if (!(yao.getWorld() instanceof ServerWorld world)) {
			return;
		}
		world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, yao.getX(), yao.getBodyY(0.5D), yao.getZ(),
				PHASE_4_COUNT, PHASE_4_HORIZONTAL_SPREAD, PHASE_4_VERTICAL_SPREAD, PHASE_4_HORIZONTAL_SPREAD,
				0.1D);
	}

	/**
	 * 有速度效果且在地面走路时在脚下拖烟花。
	 * <p>速度 I / II / III 都生效；站着不动（水平速度不够）不拖。
	 */
	public static void speedTrail(YaoYongshouEntity yao) {
		if (yao.getStatusEffect(StatusEffects.SPEED) == null) {
			return;
		}
		if (yao.age % SPEED_TRAIL_INTERVAL_TICKS != 0) {
			return;
		}
		if (!yao.isOnGround()
				|| yao.getVelocity().horizontalLengthSquared() < SPEED_TRAIL_MIN_VELOCITY_SQUARED) {
			return;
		}
		if (!(yao.getWorld() instanceof ServerWorld world)) {
			return;
		}
		// 沿身体宽度随机取一点，2 倍体型下也能盖住双脚
		double offsetX = (yao.getRandom().nextDouble() - 0.5D) * yao.getWidth();
		double offsetZ = (yao.getRandom().nextDouble() - 0.5D) * yao.getWidth();
		world.spawnParticles(ParticleTypes.FIREWORK, yao.getX() + offsetX, yao.getY() + 0.05D,
				yao.getZ() + offsetZ, SPEED_TRAIL_COUNT, yao.getWidth() * 0.1D, 0.02D,
				yao.getWidth() * 0.1D, 0.01D);
	}

	/** 近战命中：在目标脚下升起一柱尘，贴图取目标脚下方块。 */
	public static void meleeDust(YaoYongshouEntity yao, Entity target) {
		if (!(yao.getWorld() instanceof ServerWorld world)) {
			return;
		}
		BlockPos pos = target.getBlockPos();
		BlockState state = world.getBlockState(pos.down());
		if (isInvisible(state)) {
			state = world.getBlockState(pos.down(2));
		}
		if (isInvisible(state)) {
			state = Blocks.STONE.getDefaultState();
		}
		BlockStateParticleEffect effect = new BlockStateParticleEffect(ParticleTypes.DUST_PILLAR, state);
		world.spawnParticles(effect, target.getX(), target.getY() + 0.05D, target.getZ(),
				MELEE_DUST_COUNT, MELEE_DUST_SPREAD, 0.05D, MELEE_DUST_SPREAD, 0.05D);
	}

	/** dust_pillar 的贴图来自方块模型：空气与流体取不到贴图，会变成空白粒子。 */
	private static boolean isInvisible(BlockState state) {
		return state.isAir() || !state.getFluidState().isEmpty();
	}
}
