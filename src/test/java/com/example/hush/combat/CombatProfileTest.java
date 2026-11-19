package com.example.hush.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 把 docs/SPEC.md §3.1 阶段表与 §6 数值总表钉在测试里。
 * <p>
 * 这些用例只依赖 {@link CombatProfile}，不需要启动游戏；改动数值表时这里必须一起改，
 * 否则 CI 会挂——这是「文档写 40、代码写 32」这类漂移的唯一自动防线。
 */
class CombatProfileTest {
	private static final double EPSILON = 1.0E-6D;

	@Test
	@DisplayName("阶段取档：普通形态恒为 1，Boss 形态按 80% / 60% / 30% 分档")
	void phaseForFollowsHealthThresholds() {
		assertEquals(1, CombatProfile.phaseFor(1.0F, false));
		assertEquals(1, CombatProfile.phaseFor(0.5F, false), "非 Boss 形态不应该出现阶段 2+");

		assertEquals(2, CombatProfile.phaseFor(1.0F, true), "Boss 形态下限是 2");
		assertEquals(2, CombatProfile.phaseFor(0.8F, true));
		assertEquals(2, CombatProfile.phaseFor(0.6F, true));
		assertEquals(3, CombatProfile.phaseFor(0.599F, true));
		assertEquals(3, CombatProfile.phaseFor(0.301F, true));
		assertEquals(4, CombatProfile.phaseFor(0.3F, true), "30% 整属于阶段 4");
		assertEquals(4, CombatProfile.phaseFor(0.0F, true));
	}

	@Test
	@DisplayName("护甲：5 / 10 / 15 / 20，韧性恒 0")
	void armorTable() {
		assertEquals(5.0D, CombatProfile.armor(1), EPSILON);
		assertEquals(10.0D, CombatProfile.armor(2), EPSILON);
		assertEquals(15.0D, CombatProfile.armor(3), EPSILON);
		assertEquals(20.0D, CombatProfile.armor(4), EPSILON);
		assertEquals(0.0D, CombatProfile.ARMOR_TOUGHNESS, EPSILON);
	}

	@Test
	@DisplayName("近战：伤害 5/8/8/10，穿甲 0/0/4/5")
	void meleeTable() {
		assertTable(new double[] { 5, 8, 8, 10 }, CombatProfile::meleeDamage);
		assertTable(new double[] { 0, 0, 4, 5 }, CombatProfile::meleePiercing);
	}

	@Test
	@DisplayName("口水：伤害 3/4/5/6，穿甲 0/0/3/4，击退 0/0.9/1.4/1.4")
	void spitTable() {
		assertTable(new double[] { 3, 4, 5, 6 }, CombatProfile::spitDamage);
		assertTable(new double[] { 0, 0, 3, 4 }, CombatProfile::spitPiercing);
		assertTable(new double[] { 0, 0.9, 1.4, 1.4 }, CombatProfile::spitKnockback);
	}

	@Test
	@DisplayName("声波：伤害 6/8/10/14")
	void sonicTable() {
		assertTable(new double[] { 6, 8, 10, 14 }, CombatProfile::sonicDamage);
	}

	@Test
	@DisplayName("距离分段：口水 24/16，声波 17~24 / 17~32 / 17~48 / 25~64")
	void rangeTable() {
		assertTable(new double[] { 24, 16, 16, 16 }, CombatProfile::spitRange);
		assertTable(new double[] { 17, 17, 17, 25 }, CombatProfile::sonicMinRange);
		assertTable(new double[] { 24, 32, 48, 64 }, CombatProfile::sonicMaxRange);
		assertTable(new double[] { 8, 16, 16, 32 }, CombatProfile::chaseStopRange);
	}

	@Test
	@DisplayName("脱锁：阶段 2 为 32 格，阶段 3/4 永不脱锁")
	void giveUpRange() {
		assertEquals(32.0D, CombatProfile.giveUpRange(1), EPSILON);
		assertEquals(32.0D, CombatProfile.giveUpRange(2), EPSILON);
		assertEquals(Double.MAX_VALUE, CombatProfile.giveUpRange(3), EPSILON);
		assertEquals(Double.MAX_VALUE, CombatProfile.giveUpRange(4), EPSILON);
	}

	@Test
	@DisplayName("阶段 4 随机档只在 17~24 格成立")
	void randomBand() {
		assertTrue(CombatProfile.inRandomBand(4, 17.0D * 17.0D));
		assertTrue(CombatProfile.inRandomBand(4, 24.0D * 24.0D));
		assertFalse(CombatProfile.inRandomBand(4, Math.pow(16.9D, 2)));
		assertFalse(CombatProfile.inRandomBand(4, Math.pow(24.1D, 2)));
		assertFalse(CombatProfile.inRandomBand(3, 20.0D * 20.0D), "只有阶段 4 有随机档");
	}

	@Test
	@DisplayName("不变量：穿甲不超过总伤害，声波下限小于上限，阶段越高伤害不降")
	void profileInvariants() {
		double previousMelee = 0.0D;
		double previousSonic = 0.0D;
		for (int phase = 1; phase <= CombatProfile.PHASE_COUNT; phase++) {
			assertTrue(CombatProfile.meleePiercing(phase) <= CombatProfile.meleeDamage(phase),
					"阶段 " + phase + " 近战穿甲超过总伤害");
			assertTrue(CombatProfile.spitPiercing(phase) <= CombatProfile.spitDamage(phase),
					"阶段 " + phase + " 口水穿甲超过总伤害");
			assertTrue(CombatProfile.sonicMinRange(phase) < CombatProfile.sonicMaxRange(phase),
					"阶段 " + phase + " 声波区间为空");
			assertTrue(CombatProfile.meleeDamage(phase) >= previousMelee);
			assertTrue(CombatProfile.sonicDamage(phase) >= previousSonic);
			previousMelee = CombatProfile.meleeDamage(phase);
			previousSonic = CombatProfile.sonicDamage(phase);
		}
	}

	private static void assertTable(double[] expected, java.util.function.IntToDoubleFunction actual) {
		assertEquals(expected.length, CombatProfile.PHASE_COUNT, "表长度必须等于阶段数");
		for (int phase = 1; phase <= expected.length; phase++) {
			assertEquals(expected[phase - 1], actual.applyAsDouble(phase), EPSILON,
					"阶段 " + phase + " 的数值与 SPEC 不一致");
		}
	}
}
