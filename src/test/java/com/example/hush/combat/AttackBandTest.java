package com.example.hush.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AttackBandTest {
	private static double squares(double distance) {
		return distance * distance;
	}

	@Test
	void meleeWinsInsideFiveBlocks() {
		assertEquals(AttackBand.MELEE, AttackBand.forDistance(2, 0.0D));
		assertEquals(AttackBand.MELEE, AttackBand.forDistance(4, squares(CombatProfile.MELEE_RANGE)));
	}

	@Test
	void spitBandEndsWhereSonicStarts() {
		assertEquals(AttackBand.SPIT, AttackBand.forDistance(2, squares(6.0D)));
		assertEquals(AttackBand.SPIT, AttackBand.forDistance(2, squares(CombatProfile.spitRange(2))));
		assertEquals(AttackBand.SONIC, AttackBand.forDistance(2, squares(CombatProfile.sonicMinRange(2))));
	}

	@Test
	void phaseOneSpitCoversTheWholeSonicBand() {
		// 阶段 1 口水到 24 格，声波 17~24 全被口水盖住
		assertEquals(AttackBand.SPIT, AttackBand.forDistance(1, squares(CombatProfile.spitRange(1))));
		assertEquals(AttackBand.SPIT, AttackBand.forDistance(1, squares(CombatProfile.sonicMinRange(1))));
		assertEquals(AttackBand.CHASE_ONLY, AttackBand.forDistance(1, squares(CombatProfile.spitRange(1) + 1.0D)));
	}

	@Test
	void phaseFourRandomBandCoversSeventeenToTwentyFour() {
		assertEquals(AttackBand.RANDOM, AttackBand.forDistance(4, squares(CombatProfile.RANDOM_BAND_MIN_RANGE)));
		assertEquals(AttackBand.RANDOM, AttackBand.forDistance(4, squares(CombatProfile.RANDOM_BAND_MAX_RANGE)));
		assertEquals(AttackBand.SONIC, AttackBand.forDistance(4, squares(CombatProfile.sonicMinRange(4))));
	}

	@Test
	void sonicBandMatchesPhaseLimits() {
		assertEquals(AttackBand.SONIC, AttackBand.forDistance(2, squares(CombatProfile.sonicMaxRange(2))));
		assertEquals(AttackBand.CHASE_ONLY, AttackBand.forDistance(2, squares(CombatProfile.sonicMaxRange(2) + 1.0D)));
		assertEquals(AttackBand.SONIC, AttackBand.forDistance(3, squares(CombatProfile.sonicMaxRange(3))));
		assertEquals(AttackBand.SONIC, AttackBand.forDistance(4, squares(CombatProfile.sonicMaxRange(4))));
		assertEquals(AttackBand.CHASE_ONLY, AttackBand.forDistance(4, squares(CombatProfile.sonicMaxRange(4) + 1.0D)));
	}
}
