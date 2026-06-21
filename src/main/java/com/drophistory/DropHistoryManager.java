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
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reads and writes drop history to a local JSON file.
 * Stored at ~/.runelite/drop-history/drops.json.
 * File storage avoids the RuneLite config service size limit.
 *
 * Writes are expensive (the whole dataset is serialized), so they never run
 * on the game thread. {@link #recordDrop} updates the in-memory cache
 * synchronously and schedules a debounced flush on a background thread;
 * disk writes are coalesced and performed atomically via a temp file.
 */
@Slf4j
@Singleton
public class DropHistoryManager
{
    private static final Type DATA_TYPE = new TypeToken<Map<String, List<DropRecord>>>(){}.getType();
    private static final File DATA_FILE = new File(RuneLite.RUNELITE_DIR, "drop-history/drops.json");
    private static final File TEMP_FILE = new File(RuneLite.RUNELITE_DIR, "drop-history/drops.json.tmp");

    /** Coalesce rapid changes (e.g. a multi-item drop) into one disk write. */
    private static final long SAVE_DELAY_MS = 3000;

    @Inject
    private Gson gson;

    @Inject
    private ScheduledExecutorService executor;

    /** In-memory cache to avoid deserializing on every render frame. Guarded by {@code this}. */
    private Map<String, List<DropRecord>> cache = null;

    /** Pending debounced flush, if any. Guarded by {@code this}. */
    private ScheduledFuture<?> pendingSave;

    /** Serializes disk writes so a background flush and a shutdown flush never interleave. */
    private final Object diskLock = new Object();

    public synchronized void recordDrop(String itemName, int killCount, String source)
    {
        loadData().computeIfAbsent(normalize(itemName), k -> new ArrayList<>())
            .add(new DropRecord(killCount, source, System.currentTimeMillis()));
        scheduleSave();
    }

    /**
     * Merges a pre-built map of drops into the stored data. Used by the
     * importers at startup; flushed synchronously so the data is durable
     * before an importer writes its "done" flag.
     */
    public synchronized void bulkMerge(Map<String, List<DropRecord>> incoming)
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
        flushNow();
    }

    public synchronized List<DropRecord> getDrops(String itemName)
    {
        return loadData().getOrDefault(normalize(itemName), new ArrayList<>());
    }

    /**
     * Fills in an estimated kill count on a record that previously had an
     * unknown KC, identified by item and timestamp. Called from an HTTP
     * callback thread.
     */
    public synchronized void applyEstimate(String itemName, long timestamp, int killCount, String source)
    {
        List<DropRecord> records = loadData().get(normalize(itemName));
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
                scheduleSave();
                return;
            }
        }
    }

    private String normalize(String name)
    {
        return name == null ? "" : name.toLowerCase().trim();
    }

    public synchronized void invalidateCache()
    {
        cache = null;
    }

    /** Persists any pending changes immediately. Call on plugin shutdown. */
    public void flushNow()
    {
        String json;
        synchronized (this)
        {
            if (pendingSave != null)
            {
                pendingSave.cancel(false);
                pendingSave = null;
            }
            if (cache == null)
            {
                return;
            }
            json = gson.toJson(cache);
        }
        writeToDisk(json);
    }

    // -------------------------------------------------------------------------

    /** Schedules a single debounced flush. Must hold {@code this}. */
    private void scheduleSave()
    {
        if (pendingSave != null && !pendingSave.isDone())
        {
            return;
        }
        pendingSave = executor.schedule(this::flush, SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /** Background flush: snapshot under lock, write off-lock. */
    private void flush()
    {
        String json;
        synchronized (this)
        {
            pendingSave = null;
            if (cache == null)
            {
                return;
            }
            json = gson.toJson(cache);
        }
        writeToDisk(json);
    }

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

    private void writeToDisk(String json)
    {
        synchronized (diskLock)
        {
            DATA_FILE.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(TEMP_FILE))
            {
                writer.write(json);
            }
            catch (IOException e)
            {
                log.error("Failed to write drop history data", e);
                return;
            }
            try
            {
                Files.move(TEMP_FILE.toPath(), DATA_FILE.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (IOException e)
            {
                // Atomic move not supported on some setups; fall back to a plain replace.
                try
                {
                    Files.move(TEMP_FILE.toPath(), DATA_FILE.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                catch (IOException e2)
                {
                    log.error("Failed to persist drop history data", e2);
                }
            }
        }
    }
}
