package com.example.hush.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 把「玩家注视」的角度与距离边界钉在测试里：{@link GazeWatch#MAX_ANGLE_DEGREES} 与
 * {@link GazeWatch#MAX_RANGE} 改动时这里必须一起改，否则 CI 会挂。
 */
class GazeWatchTest {
	private static final double DISTANCE = 10.0D;

	@Test
	@DisplayName("正对目标：距离内为注视")
	void headOnIsWatching() {
		assertTrue(GazeWatch.isWatching(DISTANCE, 0, 0, 1, 0, 0, DISTANCE));
	}

	@Test
	@DisplayName("夹角边界：60° 以内算注视，严格大于 60° 不算")
	void angleBoundary() {
		assertTrue(GazeWatch.isWatching(DISTANCE, 0, 0, cos(30), 0, sin(30), DISTANCE), "30° 应算注视");
		assertTrue(GazeWatch.isWatching(DISTANCE, 0, 0, cos(59.5), 0, sin(59.5), DISTANCE), "59.5° 应算注视");
		assertTrue(GazeWatch.isWatching(DISTANCE, 0, 0, cos(60), 0, sin(60), DISTANCE), "正好 60° 属于边界内");
		assertFalse(GazeWatch.isWatching(DISTANCE, 0, 0, cos(60.5), 0, sin(60.5), DISTANCE), "60.5° 不算注视");
		assertFalse(GazeWatch.isWatching(DISTANCE, 0, 0, -1, 0, 0, DISTANCE), "背对目标不算注视");
	}

	@Test
	@DisplayName("距离边界：超过 32 格不算注视")
	void rangeBoundary() {
		assertTrue(GazeWatch.isWatching(GazeWatch.MAX_RANGE, 0, 0, 1, 0, 0, GazeWatch.MAX_RANGE), "32 格整仍是注视");
		double beyond = GazeWatch.MAX_RANGE + 0.5D;
		assertFalse(GazeWatch.isWatching(beyond, 0, 0, 1, 0, 0, beyond), "32.5 格不算注视");
	}

	@Test
	@DisplayName("退化输入：距离或视线为零向量时不成立")
	void degenerateInputs() {
		assertFalse(GazeWatch.isWatching(0, 0, 0, 1, 0, 0, 0), "距离 0 不算注视");
		assertFalse(GazeWatch.isWatching(0, 0, 0, 1, 0, 0, DISTANCE), "目标与玩家眼睛重合时不算注视");
	}

	@Test
	@DisplayName("视线长度不影响判定：只有方向参与计算")
	void lookVectorLengthDoesNotMatter() {
		assertTrue(GazeWatch.isWatching(DISTANCE, 0, 0, 5, 0, 0, DISTANCE));
		assertFalse(GazeWatch.isWatching(DISTANCE, 0, 0, -0.5, 0, 0, DISTANCE), "反方向即使缩短也不算");
	}

	private static double cos(double degrees) {
		return Math.cos(Math.toRadians(degrees));
	}

	private static double sin(double degrees) {
		return Math.sin(Math.toRadians(degrees));
	}
}
