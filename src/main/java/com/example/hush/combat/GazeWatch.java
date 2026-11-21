package com.example.hush.combat;

/**
 * 「玩家是否正在注视目标」的纯几何判定。
 * <p>
 * 不引用任何 Minecraft 类型，输入是玩家眼睛位置到目标的向量、玩家视线单位向量与两点距离，
 * 因此可以直接在 JUnit 里验证角度与距离边界，不需要启动游戏。
 */
public final class GazeWatch {
	/** 视为「注视」的最大夹角：视线与「玩家眼睛 → 目标」之间的角度不超过它。 */
	public static final double MAX_ANGLE_DEGREES = 60.0D;
	/** 只考虑这个距离（格）内的玩家。 */
	public static final double MAX_RANGE = 32.0D;

	private static final double MIN_COS = Math.cos(Math.toRadians(MAX_ANGLE_DEGREES));

	private GazeWatch() {
	}

	/**
	 * @param dx       玩家眼睛 → 目标的眼睛的 X 分量
	 * @param dy       同上，Y 分量
	 * @param dz       同上，Z 分量
	 * @param lx       玩家视线单位向量的 X 分量
	 * @param ly       同上，Y 分量
	 * @param lz       同上，Z 分量
	 * @param distance 玩家与目标的直线距离（格）
	 * @return 距离在 {@link #MAX_RANGE} 内且夹角不超过 {@link #MAX_ANGLE_DEGREES} 时为 {@code true}
	 */
	public static boolean isWatching(double dx, double dy, double dz,
			double lx, double ly, double lz, double distance) {
		if (distance <= 0.0D || distance > MAX_RANGE) {
			return false;
		}
		double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (length <= 0.0D) {
			return false;
		}
		// 视线与目标方向都按单位长度比较，只关心夹角
		double cosine = (dx * lx + dy * ly + dz * lz) / length;
		return cosine >= MIN_COS;
	}
}
