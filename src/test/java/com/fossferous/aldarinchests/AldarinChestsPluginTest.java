package com.fossferous.aldarinchests;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class AldarinChestsPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(AldarinChestsPlugin.class);
		RuneLite.main(args);
	}
}
