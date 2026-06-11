package com.drophistory;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(DropHistoryConfig.GROUP)
public interface DropHistoryConfig extends Config
{
    String GROUP = "drophistory";

    @ConfigItem(
        keyName = "showTooltip",
        name = "Show KC tooltip on collection log",
        description = "When hovering a collection log item, show which KC you received each drop"
    )
    default boolean showTooltip()
    {
        return true;
    }

    @ConfigItem(
        keyName = "showTimestamp",
        name = "Show date in tooltip",
        description = "Include the date alongside the KC in the tooltip"
    )
    default boolean showTimestamp()
    {
        return false;
    }

    @Range(min = 1)
    @ConfigItem(
        keyName = "maxDropsShown",
        name = "Max drops listed per item",
        description = "Items with more recorded drops than this show a short summary instead of every drop. Keeps the tooltip usable for items that drop in bulk, like Demon tears."
    )
    default int maxDropsShown()
    {
        return 25;
    }

    @ConfigItem(
        keyName = "womEstimates",
        name = "Estimate KCs via Wise Old Man",
        description = "For imported drops with an unknown KC, look up your historical boss KC on wiseoldman.net and show an estimate. Sends your display name to the Wise Old Man API."
    )
    default boolean womEstimates()
    {
        return true;
    }
}
