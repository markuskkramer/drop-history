package com.drophistory;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Watches game chat messages for KC announcements and caches the latest
 * kill count per boss so it's available when a loot event fires.
 *
 * Handles messages like:
 *   "Your Zulrah kill count is: 47."
 *   "Your Chambers of Xeric kill count is: 12."
 *   "Your Theatre of Blood completion count is: 5."
 *   "Your Barrows chest count is: 100."
 */
@Slf4j
@Singleton
public class KillCountTracker
{
    private static final Pattern KC_PATTERN = Pattern.compile(
        "Your (?<boss>.+?) (?:kill count|chest count|completion count) is: (?<kc>[\\d,]+)\\.",
        Pattern.CASE_INSENSITIVE
    );

    private final Map<String, Integer> killCounts = new HashMap<>();

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        if (event.getType() != ChatMessageType.GAMEMESSAGE
            && event.getType() != ChatMessageType.SPAM)
        {
            return;
        }

        // Strip color tags before matching
        String message = event.getMessage().replaceAll("<[^>]+>", "").trim();

        Matcher m = KC_PATTERN.matcher(message);
        if (m.matches())
        {
            String boss = m.group("boss").trim();
            try
            {
                int kc = Integer.parseInt(m.group("kc").replace(",", ""));
                killCounts.put(normalize(boss), kc);
                log.debug("KC tracked: {} -> {}", boss, kc);
            }
            catch (NumberFormatException e)
            {
                log.warn("Failed to parse KC from: {}", message);
            }
        }
    }

    /**
     * Returns the last known KC for a boss, or -1 if not yet seen.
     */
    public int getKillCount(String bossName)
    {
        return killCounts.getOrDefault(normalize(bossName), -1);
    }

    private String normalize(String name)
    {
        return name.toLowerCase().trim();
    }
}
