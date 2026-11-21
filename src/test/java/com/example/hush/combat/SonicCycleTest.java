package com.example.hush.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SonicCycleTest {
	@Test
	void firesOnChargeTick() {
		SonicCycle cycle = new SonicCycle();
		for (int i = 1; i < SonicCycle.CHARGE_TICKS; i++) {
			assertFalse(cycle.tick(), "第 " + i + " tick 不该开炮");
		}
		assertTrue(cycle.tick(), "第 " + SonicCycle.CHARGE_TICKS + " tick 该开炮");
	}

	@Test
	void firesOncePerInterval() {
		SonicCycle cycle = new SonicCycle();
		int fired = 0;
		for (int i = 1; i <= SonicCycle.INTERVAL_TICKS; i++) {
			if (cycle.tick()) {
				fired++;
			}
		}
		assertEquals(1, fired);
		assertEquals(0, cycle.ticks(), "循环走满应归零");
	}

	@Test
	void secondRoundFiresAgain() {
		SonicCycle cycle = new SonicCycle();
		for (int i = 1; i <= SonicCycle.INTERVAL_TICKS + SonicCycle.CHARGE_TICKS; i++) {
			boolean fire = cycle.tick();
			assertEquals(i == SonicCycle.CHARGE_TICKS || i == SonicCycle.INTERVAL_TICKS + SonicCycle.CHARGE_TICKS, fire,
					"第 " + i + " tick 的开炮状态不对");
		}
	}

	@Test
	void idleAndChargingFollowTickBounds() {
		SonicCycle cycle = new SonicCycle();
		assertTrue(cycle.isIdle());
		assertFalse(cycle.isCharging());
		cycle.tick();
		assertFalse(cycle.isIdle());
		assertTrue(cycle.isCharging());
		while (cycle.ticks() < SonicCycle.CHARGE_TICKS - 1) {
			cycle.tick();
		}
		assertTrue(cycle.isCharging(), "开炮前一 tick 仍在蓄力");
		cycle.tick();
		assertFalse(cycle.isCharging(), "开炮这一 tick 不再蓄力");
	}

	@Test
	void resetClearsCycle() {
		SonicCycle cycle = new SonicCycle();
		for (int i = 0; i < 50; i++) {
			cycle.tick();
		}
		cycle.reset();
		assertEquals(0, cycle.ticks());
		assertTrue(cycle.isIdle());
		int fired = 0;
		for (int i = 1; i <= SonicCycle.CHARGE_TICKS; i++) {
			if (cycle.tick()) {
				fired++;
			}
		}
		assertEquals(1, fired, "reset 后重新按 34 tick 蓄力");
	}
}
