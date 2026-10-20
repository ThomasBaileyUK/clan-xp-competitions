package com.clanxpcompetitions;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(ClanXpConfig.GROUP)
public interface ClanXpConfig extends Config
{
	// Kept the same as the old version so saved settings carry over.
	String GROUP = "clanxpcompetitions";

	String ONLINE_TRACKING_KEY = "onlineTracking";

	@ConfigItem(
		keyName = ONLINE_TRACKING_KEY,
		name = "Enable online tracking",
		description = "Lets you sign in to your clan and join competitions. Sends your username and your XP or boss kill count to the clan server.",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
		position = 1
	)
	default boolean onlineTracking()
	{
		return false;
	}
}
