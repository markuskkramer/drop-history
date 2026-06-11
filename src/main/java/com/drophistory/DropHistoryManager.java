package com.drophistory;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes drop history to a local JSON file.
 * Stored at ~/.runelite/drop-history/drops.json.
 * File storage avoids the RuneLite config service size limit.
 */
@Slf4j
@Singleton
public class DropHistoryManager
{
    private static final Type DATA_TYPE = new TypeToken<Map<String, List<DropRecord>>>(){}.getType();
    private static final File DATA_FILE = new File(RuneLite.RUNELITE_DIR, "drop-history/drops.json");

    @Inject
    private Gson gson;

    /** In-memory cache to avoid deserializing on every render frame. */
    private Map<String, List<DropRecord>> cache = null;

    public void recordDrop(String itemName, int killCount, String source)
    {
        Map<String, List<DropRecord>> data = loadData();
        data.computeIfAbsent(normalize(itemName), k -> new ArrayList<>())
            .add(new DropRecord(killCount, source, System.currentTimeMillis()));
        saveData(data);
    }

    /**
     * Merges a pre-built map of drops into the stored data in a single file write.
     */
    public void bulkMerge(Map<String, List<DropRecord>> incoming)
    {
        Map<String, List<DropRecord>> data = loadData();
        for (Map.Entry<String, List<DropRecord>> entry : incoming.entrySet())
        {
            String key = normalize(entry.getKey());
            List<DropRecord> existing = data.computeIfAbsent(key, k -> new ArrayList<>());
            for (DropRecord record : entry.getValue())
            {
                boolean dup = existing.stream().anyMatch(
                    r -> r.getKillCount() == record.getKillCount()
                        && record.getSource().equalsIgnoreCase(r.getSource())
                );
                if (!dup)
                {
                    existing.add(record);
                }
            }
        }
        saveData(data);
    }

    public List<DropRecord> getDrops(String itemName)
    {
        return loadData().getOrDefault(normalize(itemName), new ArrayList<>());
    }

    /**
     * Fills in an estimated kill count on a record that previously had an
     * unknown KC, identified by item and timestamp. Called from an HTTP
     * callback thread, hence synchronized.
     */
    public synchronized void applyEstimate(String itemName, long timestamp, int killCount, String source)
    {
        Map<String, List<DropRecord>> data = loadData();
        List<DropRecord> records = data.get(normalize(itemName));
        if (records == null)
        {
            return;
        }
        for (DropRecord record : records)
        {
            if (record.getTimestamp() == timestamp && record.getKillCount() == -1)
            {
                record.setKillCount(killCount);
                record.setSource(source);
                record.setEstimated(true);
                saveData(data);
                return;
            }
        }
    }

    private String normalize(String name)
    {
        return name == null ? "" : name.toLowerCase().trim();
    }

    public void invalidateCache()
    {
        cache = null;
    }

    // -------------------------------------------------------------------------

    private Map<String, List<DropRecord>> loadData()
    {
        if (cache != null)
        {
            return cache;
        }
        if (!DATA_FILE.exists())
        {
            cache = new HashMap<>();
            return cache;
        }
        try (FileReader reader = new FileReader(DATA_FILE))
        {
            cache = gson.fromJson(reader, DATA_TYPE);
            if (cache == null) cache = new HashMap<>();
        }
        catch (Exception e)
        {
            log.warn("Failed to load drop history data", e);
            cache = new HashMap<>();
        }
        return cache;
    }

    private void saveData(Map<String, List<DropRecord>> data)
    {
        cache = data;
        DATA_FILE.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(DATA_FILE))
        {
            gson.toJson(data, writer);
        }
        catch (IOException e)
        {
            log.error("Failed to save drop history data", e);
        }
    }
}
