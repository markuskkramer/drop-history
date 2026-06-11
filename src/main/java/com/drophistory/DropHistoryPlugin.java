package com.drophistory;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.util.Collection;

@Slf4j
@PluginDescriptor(
    name = "Drop History",
    description = "Tracks what KC you received each collection log item at. Hover items in your collection log to see the full history.",
    tags = {"collection log", "drop", "kc", "kill count", "loot", "tracker"}
)
public class DropHistoryPlugin extends Plugin
{
    @Inject private DropHistoryConfig config;
    @Inject private DropHistoryManager manager;
    @Inject private DropHistoryOverlay overlay;
    @Inject private KillCountTracker kcTracker;
    @Inject private LootTrackerImporter lootTrackerImporter;
    @Inject private ScreenshotImporter screenshotImporter;
    @Inject private OverlayManager overlayManager;
    @Inject private ItemManager itemManager;
    @Inject private EventBus eventBus;

    @Override
    protected void startUp()
    {
        manager.invalidateCache();
        overlayManager.add(overlay);
        eventBus.register(kcTracker);
        // Order matters: the loot tracker import provides exact KCs, so it
        // runs first and the screenshot import dedupes against its records.
        lootTrackerImporter.runIfNeeded();
        screenshotImporter.runIfNeeded();
        log.debug("Drop History started");
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(overlay);
        eventBus.unregister(kcTracker);
        log.debug("Drop History stopped");
    }

    @Provides
    DropHistoryConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(DropHistoryConfig.class);
    }

    /**
     * Fires when loot is received from an NPC kill.
     * Covers most PvM content: bosses, slayer, etc.
     */
    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event)
    {
        String source = event.getNpc().getName();
        if (source == null)
        {
            return;
        }

        int kc = kcTracker.getKillCount(source);
        Collection<ItemStack> items = event.getItems();

        for (ItemStack stack : items)
        {
            String itemName = itemManager.getItemComposition(stack.getId()).getName();
            if (itemName == null || itemName.isEmpty())
            {
                continue;
            }
            log.debug("Recording drop: {} from {} at KC {}", itemName, source, kc);
            manager.recordDrop(itemName, kc, source);
        }
    }

    /**
     * Invalidate cache on login so config service changes from other devices are picked up.
     */
    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            manager.invalidateCache();
        }
    }
}
