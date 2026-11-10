package com.fossferous.aldarinchests;

import static com.fossferous.aldarinchests.ChestTracker.Result.FAIL;
import static com.fossferous.aldarinchests.ChestTracker.Result.SUCCESS;
import static com.fossferous.aldarinchests.ChestTracker.Result.TRAP;
import static com.fossferous.aldarinchests.ChestTracker.State.BUSY;
import static com.fossferous.aldarinchests.ChestTracker.State.OPEN;
import static com.fossferous.aldarinchests.ChestTracker.State.READY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;

/**
 * Tick sequences in these tests are copied from an in-game log of the western villa chest
 * (1350, 2947, 1) on 2026-11-10.
 */
public class ChestTrackerTest
{
	private static final WorldPoint WEST = new WorldPoint(1350, 2947, 1);
	private static final WorldPoint EAST = new WorldPoint(1378, 2960, 1);

	private ChestTracker tracker;

	@Before
	public void setUp()
	{
		tracker = new ChestTracker();
		tracker.chestSeen(WEST, false, 0);
		tracker.chestSeen(EAST, false, 0);
	}

	@Test
	public void closedChestIsReady()
	{
		assertEquals(READY, tracker.stateOf(WEST));
		assertEquals(-1, tracker.ticksLeft(WEST, 0));
	}

	@Test
	public void failIsReadyOnTheResultTick()
	{
		// tick 131: click + animation, tick 133: fail
		assertTrue(tracker.clicked(WEST, 131));
		tracker.animationStarted(WEST, 131);
		assertEquals(BUSY, tracker.stateOf(WEST));
		assertEquals(READY, tracker.stateOf(EAST));
		assertEquals(2, tracker.ticksLeft(WEST, 131));
		assertEquals(1, tracker.ticksLeft(WEST, 132));

		tracker.result(FAIL, WEST, 133);
		assertEquals(READY, tracker.stateOf(WEST));
	}

	@Test
	public void walkingToTheChestHasNoCountdownUntilTheAnimation()
	{
		// tick 40: click from a distance, 42: animation, 44: fail
		tracker.clicked(WEST, 40);
		assertEquals(BUSY, tracker.stateOf(WEST));
		assertEquals(-1, tracker.ticksLeft(WEST, 41));
		tracker.animationStarted(WEST, 42);
		assertEquals(2, tracker.ticksLeft(WEST, 42));
		tracker.result(FAIL, WEST, 44);
		assertEquals(2, tracker.getAttemptTicks());
	}

	@Test
	public void clickDuringAnAttemptIsIgnored()
	{
		// tick 216: click + animation, 217: click again (no effect in game), 218: fail
		tracker.clicked(WEST, 216);
		tracker.animationStarted(WEST, 216);
		assertFalse(tracker.clicked(WEST, 217));
		assertEquals(1, tracker.ticksLeft(WEST, 217));
		tracker.result(FAIL, WEST, 218);
		assertEquals(READY, tracker.stateOf(WEST));
	}

	@Test
	public void successStaysOpenUntilTheChestClosesAgain()
	{
		// tick 125: click + animation, 127: success, 128: open object, 130: closed object
		tracker.clicked(WEST, 125);
		tracker.animationStarted(WEST, 125);
		tracker.result(SUCCESS, WEST, 127);

		// the chest is still the closed object, but clicking it now does nothing
		assertEquals(OPEN, tracker.stateOf(WEST));
		assertEquals(3, tracker.ticksLeft(WEST, 127));
		assertFalse(tracker.clicked(WEST, 127));

		tracker.chestSeen(WEST, true, 128);
		assertEquals(OPEN, tracker.stateOf(WEST));
		assertEquals(2, tracker.ticksLeft(WEST, 128));
		assertEquals(1, tracker.ticksLeft(WEST, 129));
		assertFalse(tracker.clicked(WEST, 129));

		tracker.chestSeen(WEST, false, 130);
		assertEquals(READY, tracker.stateOf(WEST));
		assertTrue(tracker.clicked(WEST, 131));
		assertEquals(1, tracker.getOpenDelayTicks());
		assertEquals(2, tracker.getRespawnTicks());
	}

	@Test
	public void ignoredClickOnOpenChestDoesNotBlockTheNextOne()
	{
		// tick 48: success, 49: click while it opens (ignored), 51: closed, 52: real attempt
		tracker.animationStarted(WEST, 46);
		tracker.result(SUCCESS, WEST, 48);
		tracker.chestSeen(WEST, true, 49);
		assertFalse(tracker.clicked(WEST, 49));
		tracker.chestSeen(WEST, false, 51);
		assertEquals(READY, tracker.stateOf(WEST));
		assertTrue(tracker.clicked(WEST, 52));
	}

	@Test
	public void trapIsBusyFromTheFailUntilTheTrapMessage()
	{
		// tick 52: click + animation, 54: fail + teleport animation, 56: trap
		tracker.clicked(WEST, 52);
		tracker.animationStarted(WEST, 52);
		tracker.result(FAIL, WEST, 54);
		tracker.teleportStarted(WEST, 54);
		assertEquals(BUSY, tracker.stateOf(WEST));
		assertEquals(2, tracker.ticksLeft(WEST, 54));
		assertEquals(1, tracker.ticksLeft(WEST, 55));
		assertFalse(tracker.clicked(WEST, 55));

		tracker.result(TRAP, WEST, 56);
		assertEquals(READY, tracker.stateOf(WEST));
		assertEquals(2, tracker.getTrapTicks());
	}

	@Test
	public void teleportWithoutAFailIsNotATrap()
	{
		tracker.teleportStarted(WEST, 300);
		assertEquals(READY, tracker.stateOf(WEST));
	}

	@Test
	public void someoneElseLootingTheChestShowsOpenWithoutLearningFromIt()
	{
		// tick 151: open object without your success, 152: closed
		tracker.chestSeen(WEST, true, 151);
		assertEquals(OPEN, tracker.stateOf(WEST));
		assertEquals(ChestTracker.DEFAULT_RESPAWN_TICKS, tracker.ticksLeft(WEST, 151));
		tracker.chestSeen(WEST, false, 152);
		assertEquals(READY, tracker.stateOf(WEST));
		assertEquals(ChestTracker.DEFAULT_RESPAWN_TICKS, tracker.getRespawnTicks());
	}

	@Test
	public void yourSuccessWhileSomeoneElsesLootHasItOpenStillWaitsForYourOpen()
	{
		// second session, tick 28: your click + animation, someone else's loot opens it;
		// 30: your success, their open closes; 31: your click (dropped) and your open; 33: closed; 35: real attempt
		assertTrue(tracker.clicked(WEST, 28));
		tracker.animationStarted(WEST, 28);
		tracker.chestSeen(WEST, true, 28);
		assertEquals(OPEN, tracker.stateOf(WEST));

		tracker.result(SUCCESS, WEST, 30);
		tracker.chestSeen(WEST, false, 30);
		assertEquals(OPEN, tracker.stateOf(WEST));
		assertFalse(tracker.clicked(WEST, 31));

		tracker.chestSeen(WEST, true, 31);
		tracker.chestSeen(WEST, false, 33);
		assertEquals(READY, tracker.stateOf(WEST));
		assertTrue(tracker.clicked(WEST, 35));
		assertEquals(2, tracker.getRespawnTicks());
	}

	@Test
	public void clickOnTheTickSomeoneElseLootsItIsDropped()
	{
		// second session, tick 199: your click, then someone else's loot opens it; 201: closed;
		// 202: your next click counts; 204: animation; 206: success
		assertTrue(tracker.clicked(WEST, 199));
		tracker.chestSeen(WEST, true, 199);
		assertEquals(OPEN, tracker.stateOf(WEST));
		tracker.chestSeen(WEST, false, 201);
		assertEquals(READY, tracker.stateOf(WEST));
		assertTrue(tracker.clicked(WEST, 202));
		tracker.animationStarted(WEST, 204);
		assertEquals(2, tracker.ticksLeft(WEST, 204));
	}

	@Test
	public void clickingAgainWhileWalkingReplacesTheClick()
	{
		assertTrue(tracker.clicked(WEST, 400));
		assertTrue(tracker.clicked(WEST, 402));
		tracker.tick(402 + ChestTracker.ATTEMPT_TIMEOUT_TICKS);
		assertEquals(BUSY, tracker.stateOf(WEST));
	}

	@Test
	public void startedAttemptWithoutAResultIsDroppedSoon()
	{
		tracker.clicked(WEST, 450);
		tracker.animationStarted(WEST, 450);
		tracker.tick(450 + ChestTracker.DEFAULT_ATTEMPT_TICKS + ChestTracker.RESULT_GRACE_TICKS);
		assertEquals(BUSY, tracker.stateOf(WEST));
		tracker.tick(451 + ChestTracker.DEFAULT_ATTEMPT_TICKS + ChestTracker.RESULT_GRACE_TICKS);
		assertEquals(READY, tracker.stateOf(WEST));
	}

	@Test
	public void chestFirstSeenOpenHasNoCountdown()
	{
		WorldPoint other = new WorldPoint(1351, 2953, 2);
		tracker.chestSeen(other, true, 500);
		assertEquals(OPEN, tracker.stateOf(other));
		assertEquals(-1, tracker.ticksLeft(other, 500));
	}

	@Test
	public void resultWithoutASeenStartUsesTheFallbackChest()
	{
		tracker.result(SUCCESS, WEST, 600);
		assertEquals(OPEN, tracker.stateOf(WEST));
	}

	@Test
	public void timeoutsDropMissedFollowUps()
	{
		tracker.clicked(WEST, 700);
		tracker.tick(700 + ChestTracker.ATTEMPT_TIMEOUT_TICKS);
		assertEquals(BUSY, tracker.stateOf(WEST));
		tracker.tick(701 + ChestTracker.ATTEMPT_TIMEOUT_TICKS);
		assertEquals(READY, tracker.stateOf(WEST));

		tracker.result(SUCCESS, EAST, 800);
		tracker.tick(801 + ChestTracker.PENDING_TIMEOUT_TICKS);
		assertEquals(READY, tracker.stateOf(EAST));
	}

	@Test
	public void implausibleDurationsAreIgnored()
	{
		tracker.animationStarted(WEST, 900);
		tracker.result(FAIL, WEST, 950);
		assertEquals(ChestTracker.DEFAULT_ATTEMPT_TICKS, tracker.getAttemptTicks());
	}

	@Test
	public void clearForgetsEverything()
	{
		tracker.clicked(WEST, 1000);
		tracker.clear();
		assertEquals(READY, tracker.stateOf(WEST));
		assertFalse(tracker.isTracked(WEST));
	}
}
