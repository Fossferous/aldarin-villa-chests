/*
 * Copyright (c) 2026, Fossferous
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.fossferous.aldarinchests;

import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.runelite.api.coords.WorldPoint;

/**
 * Tick-based state for the Aldarin villa chests. It has no client dependency, so every
 * method takes the current game tick and the plugin feeds it events.
 *
 * <p>Timeline measured in game (ticks are 0.6 s):
 * <ul>
 * <li>Attempt: the picklock animation starts, and the fail or success message arrives 2 ticks later.
 * Clicks during an attempt are ignored by the game.</li>
 * <li>Success: the chest is still the closed object on the success tick, swaps to the open object
 * 1 tick later and back to closed 2 ticks after that. Clicks while it is open are ignored.</li>
 * <li>Trap: the fail message, then the teleport animation on the same tick, then the trap message
 * 2 ticks later.</li>
 * <li>Other players: their loot opens the chest the same way. A click that lands while it is open is
 * dropped, but an attempt already running still finishes, and your own success still opens it again.</li>
 * </ul>
 * A chest is READY only when a click on it would start a new attempt. Durations are re-measured
 * as they happen, so countdowns follow any timing change.
 */
final class ChestTracker
{
	static final int DEFAULT_ATTEMPT_TICKS = 2;
	static final int DEFAULT_OPEN_DELAY_TICKS = 1;
	static final int DEFAULT_RESPAWN_TICKS = 2;
	static final int DEFAULT_TRAP_TICKS = 2;
	/** A click that never turns into an attempt is dropped after this many ticks (walked away, interrupted). */
	static final int ATTEMPT_TIMEOUT_TICKS = 10;
	/** A started attempt whose result message never arrives is dropped this many ticks after it was due. */
	static final int RESULT_GRACE_TICKS = 3;
	/** A success whose open chest never appears, or a teleport with no trap message, is dropped after this. */
	static final int PENDING_TIMEOUT_TICKS = 5;
	/** Ignore measured durations outside this range; they come from missed events. */
	private static final int MAX_LEARNED_TICKS = 20;

	enum State
	{
		READY,
		BUSY,
		OPEN
	}

	enum Result
	{
		FAIL,
		SUCCESS,
		TRAP
	}

	private static final class Chest
	{
		private boolean open;
		/** Tick of the last open/closed change, or -1 when the chest was first seen in its current state. */
		private int changedTick = -1;
		/** Tick of your success on this chest while it has not opened yet, or -1. */
		private int successTick = -1;
		/** True while the chest is open because of your own success, so its closing time can be measured. */
		private boolean openedByYou;
	}

	private final Map<WorldPoint, Chest> chests = new HashMap<>();

	private WorldPoint attemptChest;
	private int clickTick = -1;
	private int animationTick = -1;

	private WorldPoint lastFailChest;
	private int lastFailTick = -1;

	private WorldPoint trapChest;
	private int trapTick = -1;

	private int attemptTicks = DEFAULT_ATTEMPT_TICKS;
	private int openDelayTicks = DEFAULT_OPEN_DELAY_TICKS;
	private int respawnTicks = DEFAULT_RESPAWN_TICKS;
	private int trapTicks = DEFAULT_TRAP_TICKS;

	/** Called when a chest object spawns (scene load, or the game swapping open/closed). */
	void chestSeen(WorldPoint location, boolean open, int tick)
	{
		Chest chest = chests.get(location);
		if (chest == null)
		{
			chest = new Chest();
			chest.open = open;
			chests.put(location, chest);
			return;
		}

		if (chest.open == open)
		{
			return;
		}

		if (open)
		{
			chest.openedByYou = chest.successTick >= 0;
			if (chest.openedByYou)
			{
				openDelayTicks = learn(openDelayTicks, tick - chest.successTick);
			}
			chest.successTick = -1;

			// someone else looted it before your click turned into an attempt: the game drops that click
			if (location.equals(attemptChest) && animationTick < 0)
			{
				attemptChest = null;
				clickTick = -1;
			}
		}
		else if (chest.openedByYou && chest.changedTick >= 0)
		{
			respawnTicks = learn(respawnTicks, tick - chest.changedTick);
			chest.openedByYou = false;
		}

		chest.open = open;
		chest.changedTick = tick;
	}

	/**
	 * You clicked an option on this chest. Returns false when the game will ignore the click
	 * because an attempt or trap is still running, or the chest is open or about to open.
	 * A click that has not turned into an attempt yet (still walking) is simply replaced.
	 */
	boolean clicked(WorldPoint chest, int tick)
	{
		if (animationTick >= 0 || trapTick >= 0)
		{
			return false;
		}
		Chest state = chests.get(chest);
		if (state != null && (state.open || state.successTick >= 0))
		{
			return false;
		}
		attemptChest = chest;
		clickTick = tick;
		animationTick = -1;
		return true;
	}

	/** Your character started the picklock animation next to this chest. */
	void animationStarted(WorldPoint chest, int tick)
	{
		if (animationTick >= 0 && chest.equals(attemptChest))
		{
			// the animation looping within one attempt
			return;
		}
		attemptChest = chest;
		animationTick = tick;
		if (clickTick < 0)
		{
			clickTick = tick;
		}
	}

	/** Your character started a teleport animation next to this chest. Only a trap if it follows a fail. */
	void teleportStarted(WorldPoint chest, int tick)
	{
		if (lastFailTick >= 0 && tick - lastFailTick <= 1 && chest.equals(lastFailChest))
		{
			trapChest = chest;
			trapTick = tick;
		}
	}

	/**
	 * The game reported the outcome of an attempt. {@code fallbackChest} is used when the attempt's
	 * start was missed, for example when the plugin was turned on mid-attempt.
	 */
	void result(Result result, @Nullable WorldPoint fallbackChest, int tick)
	{
		WorldPoint chest = attemptChest != null ? attemptChest : fallbackChest;

		switch (result)
		{
			case FAIL:
				learnAttemptFrom(tick);
				lastFailChest = chest;
				lastFailTick = tick;
				break;
			case SUCCESS:
				learnAttemptFrom(tick);
				Chest state = chest == null ? null : chests.get(chest);
				if (state != null)
				{
					// even if someone else's loot has it open right now, your own open follows
					state.successTick = tick;
				}
				break;
			case TRAP:
				if (trapTick >= 0)
				{
					trapTicks = learn(trapTicks, tick - trapTick);
				}
				trapChest = null;
				trapTick = -1;
				break;
		}

		attemptChest = null;
		clickTick = -1;
		animationTick = -1;
	}

	/** Called every game tick to drop anything whose follow-up event never came. */
	void tick(int tick)
	{
		boolean clickExpired = clickTick >= 0 && tick - clickTick > ATTEMPT_TIMEOUT_TICKS;
		boolean attemptExpired = animationTick >= 0 && tick - animationTick > attemptTicks + RESULT_GRACE_TICKS;
		if (clickExpired || attemptExpired)
		{
			attemptChest = null;
			clickTick = -1;
			animationTick = -1;
		}
		if (trapTick >= 0 && tick - trapTick > PENDING_TIMEOUT_TICKS)
		{
			trapChest = null;
			trapTick = -1;
		}
		for (Chest chest : chests.values())
		{
			if (chest.successTick >= 0 && tick - chest.successTick > PENDING_TIMEOUT_TICKS)
			{
				chest.successTick = -1;
			}
		}
	}

	/** Scene reload or logout: object spawns will repopulate the chests. */
	void clear()
	{
		chests.clear();
		attemptChest = null;
		clickTick = -1;
		animationTick = -1;
		lastFailChest = null;
		lastFailTick = -1;
		trapChest = null;
		trapTick = -1;
	}

	State stateOf(WorldPoint location)
	{
		Chest chest = chests.get(location);
		if (chest != null && (chest.open || chest.successTick >= 0))
		{
			return State.OPEN;
		}
		if (trapTick >= 0 && location.equals(trapChest))
		{
			return State.BUSY;
		}
		if (clickTick >= 0 && location.equals(attemptChest))
		{
			return State.BUSY;
		}
		return State.READY;
	}

	/**
	 * Whole game ticks until this chest is expected to be READY, or -1 when unknown
	 * (READY already, first seen open, or clicked but the attempt has not started yet).
	 */
	int ticksLeft(WorldPoint location, int tick)
	{
		Chest chest = chests.get(location);
		if (chest != null && chest.successTick >= 0 && !chest.open)
		{
			return Math.max(0, openDelayTicks + respawnTicks - (tick - chest.successTick));
		}
		if (chest != null && chest.open)
		{
			return chest.changedTick < 0 ? -1 : Math.max(0, respawnTicks - (tick - chest.changedTick));
		}
		if (trapTick >= 0 && location.equals(trapChest))
		{
			return Math.max(0, trapTicks - (tick - trapTick));
		}
		if (clickTick >= 0 && animationTick >= 0 && location.equals(attemptChest))
		{
			return Math.max(0, attemptTicks - (tick - animationTick));
		}
		return -1;
	}

	@Nullable
	WorldPoint getAttemptChest()
	{
		return attemptChest;
	}

	boolean isTracked(WorldPoint location)
	{
		return chests.containsKey(location);
	}

	int getAttemptTicks()
	{
		return attemptTicks;
	}

	int getOpenDelayTicks()
	{
		return openDelayTicks;
	}

	int getRespawnTicks()
	{
		return respawnTicks;
	}

	int getTrapTicks()
	{
		return trapTicks;
	}

	private void learnAttemptFrom(int tick)
	{
		if (animationTick >= 0)
		{
			attemptTicks = learn(attemptTicks, tick - animationTick);
		}
	}

	private static int learn(int current, int measured)
	{
		return measured >= 1 && measured <= MAX_LEARNED_TICKS ? measured : current;
	}
}
