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

import com.google.inject.Provides;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.MenuAction;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.Notifier;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "Aldarin Villa Chests",
	description = "Colours the Aldarin villa thieving chests by whether you can click them now, with a tick countdown",
	tags = {"thieving", "aldarin", "villa", "chest", "varlamore", "tick", "timer"}
)
public class AldarinChestsPlugin extends Plugin
{
	static final int CLOSED_CHEST = ObjectID.ALDARIN_THIEVING_CHEST;
	static final int OPEN_CHEST = ObjectID.ALDARIN_THIEVING_CHEST_OPEN;

	private static final String MESSAGE_FAIL = "You fail to picklock the chest";
	private static final String MESSAGE_SUCCESS = "You manage to unlock the chest";
	private static final String MESSAGE_TRAP = "You have activated a trap on the chest";

	/** The picklock animation plays from a tile next to the chest. */
	private static final int ANIMATION_RANGE = 2;

	@Inject
	private Client client;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private AldarinChestsOverlay overlay;

	@Inject
	private AldarinChestsConfig config;

	@Inject
	private Notifier notifier;

	private final ChestTracker tracker = new ChestTracker();
	private final Map<WorldPoint, GameObject> chests = new HashMap<>();

	/** The chest you last started an attempt on, for the ready notification. */
	private WorldPoint activeChest;
	private ChestTracker.State activeState;
	private boolean trappedThisTick;

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		chests.clear();
		tracker.clear();
		resetActiveChest();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case LOADING:
			case LOGIN_SCREEN:
			case HOPPING:
				chests.clear();
				tracker.clear();
				resetActiveChest();
				break;
			default:
				break;
		}
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		GameObject object = event.getGameObject();
		int id = object.getId();
		if (id != CLOSED_CHEST && id != OPEN_CHEST)
		{
			return;
		}

		WorldPoint location = object.getWorldLocation();
		chests.put(location, object);
		tracker.chestSeen(location, id == OPEN_CHEST, client.getTickCount());
		log.debug("tick {}: chest {} at {}", client.getTickCount(), id == OPEN_CHEST ? "open" : "closed", location);
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned event)
	{
		GameObject object = event.getGameObject();
		WorldPoint location = object.getWorldLocation();
		// the open and closed objects swap on the same tile, so only drop the one we are holding
		if (chests.get(location) == object)
		{
			chests.remove(location);
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (event.getId() != CLOSED_CHEST || !isObjectOption(event.getMenuAction()))
		{
			return;
		}

		WorldPoint clicked = WorldPoint.fromScene(client.getTopLevelWorldView(), event.getParam0(), event.getParam1(),
			client.getTopLevelWorldView().getPlane());
		WorldPoint chest = nearestChest(clicked, 1);
		if (chest != null)
		{
			boolean counted = tracker.clicked(chest, client.getTickCount());
			if (counted)
			{
				setActiveChest(chest);
			}
			log.debug("tick {}: clicked '{}' on chest at {}{}", client.getTickCount(), event.getMenuOption(), chest,
				counted ? "" : " (ignored: chest not ready)");
		}
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		Player player = client.getLocalPlayer();
		if (player == null || event.getActor() != player)
		{
			return;
		}

		int animation = player.getAnimation();
		if (animation == -1)
		{
			return;
		}

		WorldPoint chest = nearestChest(player.getWorldLocation(), ANIMATION_RANGE);
		if (chest == null)
		{
			return;
		}

		int tick = client.getTickCount();
		switch (animation)
		{
			case AnimationID.HUMAN_LOCKEDCHEST:
				tracker.animationStarted(chest, tick);
				setActiveChest(chest);
				log.debug("tick {}: picklock animation at {}", tick, chest);
				break;
			case AnimationID.HUMAN_CASTTELEPORT:
				tracker.teleportStarted(chest, tick);
				log.debug("tick {}: teleport animation at {}", tick, chest);
				break;
			default:
				log.debug("tick {}: other animation {} next to a chest", tick, animation);
				break;
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}

		String message = Text.removeTags(event.getMessage());
		ChestTracker.Result result;
		if (message.startsWith(MESSAGE_FAIL))
		{
			result = ChestTracker.Result.FAIL;
		}
		else if (message.startsWith(MESSAGE_SUCCESS))
		{
			result = ChestTracker.Result.SUCCESS;
		}
		else if (message.startsWith(MESSAGE_TRAP))
		{
			result = ChestTracker.Result.TRAP;
		}
		else
		{
			return;
		}

		Player player = client.getLocalPlayer();
		WorldPoint fallback = player == null ? null : nearestChest(player.getWorldLocation(), ANIMATION_RANGE);
		tracker.result(result, fallback, client.getTickCount());
		if (result == ChestTracker.Result.TRAP)
		{
			trappedThisTick = true;
		}
		log.debug("tick {}: result {} (learned ticks: attempt {}, open delay {}, closing {}, trap {})",
			client.getTickCount(), result, tracker.getAttemptTicks(), tracker.getOpenDelayTicks(),
			tracker.getRespawnTicks(), tracker.getTrapTicks());
	}

	/**
	 * Runs after every packet of the server tick, so a fail followed by a trap teleport on the same
	 * tick is seen as a trap, never as a ready chest.
	 */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		tracker.tick(client.getTickCount());

		if (trappedThisTick)
		{
			trappedThisTick = false;
			resetActiveChest();
			notifier.notify(config.trapNotification(), "Trapped: head back up the stairs");
			return;
		}

		if (activeChest == null)
		{
			return;
		}

		ChestTracker.State state = tracker.stateOf(activeChest);
		Player player = client.getLocalPlayer();
		if (state == ChestTracker.State.READY && activeState != ChestTracker.State.READY
			&& player != null && activeChest.distanceTo(player.getWorldLocation()) <= ANIMATION_RANGE)
		{
			notifier.notify(config.readyNotification(), "Chest ready");
		}
		activeState = state;
	}

	private void setActiveChest(WorldPoint chest)
	{
		activeChest = chest;
		activeState = tracker.stateOf(chest);
	}

	private void resetActiveChest()
	{
		activeChest = null;
		activeState = null;
		trappedThisTick = false;
	}

	Map<WorldPoint, GameObject> getChests()
	{
		return Collections.unmodifiableMap(chests);
	}

	ChestTracker getTracker()
	{
		return tracker;
	}

	private WorldPoint nearestChest(WorldPoint from, int maxDistance)
	{
		WorldPoint best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (WorldPoint location : chests.keySet())
		{
			int distance = location.distanceTo(from);
			if (distance <= maxDistance && distance < bestDistance)
			{
				best = location;
				bestDistance = distance;
			}
		}
		return best;
	}

	private static boolean isObjectOption(MenuAction action)
	{
		switch (action)
		{
			case GAME_OBJECT_FIRST_OPTION:
			case GAME_OBJECT_SECOND_OPTION:
			case GAME_OBJECT_THIRD_OPTION:
			case GAME_OBJECT_FOURTH_OPTION:
			case GAME_OBJECT_FIFTH_OPTION:
				return true;
			default:
				return false;
		}
	}

	@Provides
	AldarinChestsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(AldarinChestsConfig.class);
	}
}
