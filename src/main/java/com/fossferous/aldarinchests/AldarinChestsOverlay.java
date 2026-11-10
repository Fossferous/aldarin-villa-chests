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

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import net.runelite.client.util.ColorUtil;

class AldarinChestsOverlay extends Overlay
{
	/** Chests further away than this are off screen in the villas anyway. */
	private static final int MAX_DISTANCE = 20;
	private static final int FILL_ALPHA = 50;

	private final Client client;
	private final AldarinChestsPlugin plugin;
	private final AldarinChestsConfig config;
	private final ModelOutlineRenderer modelOutlineRenderer;

	@Inject
	AldarinChestsOverlay(Client client, AldarinChestsPlugin plugin, AldarinChestsConfig config,
		ModelOutlineRenderer modelOutlineRenderer)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.modelOutlineRenderer = modelOutlineRenderer;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return null;
		}

		WorldPoint playerLocation = player.getWorldLocation();
		ChestTracker tracker = plugin.getTracker();
		int tick = client.getTickCount();

		for (Map.Entry<WorldPoint, GameObject> entry : plugin.getChests().entrySet())
		{
			WorldPoint location = entry.getKey();
			GameObject chest = entry.getValue();
			if (location.getPlane() != playerLocation.getPlane() || location.distanceTo(playerLocation) > MAX_DISTANCE)
			{
				continue;
			}

			ChestTracker.State state = tracker.stateOf(location);
			Color color = colorFor(state);

			modelOutlineRenderer.drawOutline(chest, config.outlineWidth(), color, 0);

			if (config.fillClickbox())
			{
				Shape clickbox = chest.getClickbox();
				if (clickbox != null)
				{
					graphics.setColor(ColorUtil.colorWithAlpha(color, Math.min(color.getAlpha(), FILL_ALPHA)));
					graphics.fill(clickbox);
				}
			}

			if (config.showCountdown())
			{
				int ticksLeft = tracker.ticksLeft(location, tick);
				if (ticksLeft > 0)
				{
					String text = Integer.toString(ticksLeft);
					Point textLocation = chest.getCanvasTextLocation(graphics, text, 0);
					if (textLocation != null)
					{
						OverlayUtil.renderTextLocation(graphics, textLocation, text, color);
					}
				}
			}
		}

		return null;
	}

	private Color colorFor(ChestTracker.State state)
	{
		switch (state)
		{
			case BUSY:
				return config.busyColor();
			case OPEN:
				return config.openColor();
			default:
				return config.readyColor();
		}
	}
}
