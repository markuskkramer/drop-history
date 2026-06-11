package com.drophistory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One-time importer that reads RuneLite's loot tracker .log files and
 * backfills DropHistory with historical KC data.
 *
 * Builds the full dataset in memory first, then flushes in a single file write
 * to avoid race conditions with invalidateCache() during login.
 *
 * The import is only marked complete once at least one record has been
 * imported; if no local loot tracker data exists yet, it retries on every
 * startup so data added later is still picked up.
 */
@Slf4j
@Singleton
public class LootTrackerImporter
{
    private static final File IMPORTED_FLAG = new File(RuneLite.RUNELITE_DIR, "drop-history/imported.flag");
    private static final SimpleDateFormat LOG_DATE_FMT =
        new SimpleDateFormat("MMM d, yyyy, h:mm:ss a");

    @Inject private DropHistoryManager manager;

    public void runIfNeeded()
    {
        if (IMPORTED_FLAG.exists())
        {
            log.debug("Loot tracker import already done, skipping");
            return;
        }

        File lootsDir = new File(RuneLite.RUNELITE_DIR, "loots");
        File[] profiles = lootsDir.exists()
            ? lootsDir.listFiles(f -> f.isDirectory() && f.getName().matches("\\d+"))
            : null;
        if (profiles == null || profiles.length == 0)
        {
            // Don't mark done — the loot tracker may write data later,
            // so check again on the next startup.
            log.info("No local loot tracker data found at {} — historical import skipped, will retry next startup", lootsDir);
            return;
        }

        // Build entire import in memory — no file I/O until the very end.
        Map<String, List<DropRecord>> incoming = new HashMap<>();
        int totalRecords = 0;

        for (File profile : profiles)
        {
            for (String subDir : new String[]{"npc", "event"})
            {
                File dir = new File(profile, subDir);
                if (!dir.exists()) continue;

                File[] logs = dir.listFiles(f -> f.getName().endsWith(".log"));
                if (logs == null) continue;

                for (File logFile : logs)
                {
                    totalRecords += parseLogFile(logFile, incoming);
                }
            }
        }

        if (totalRecords == 0)
        {
            log.info("Loot tracker logs contained no importable drops — will retry next startup");
            return;
        }

        // Single write for all imported data
        manager.bulkMerge(incoming);
        log.info("Loot tracker import complete — {} drop records imported", totalRecords);
        markDone();
    }

    private int parseLogFile(File logFile, Map<String, List<DropRecord>> incoming)
    {
        int count = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(logFile)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                line = line.trim();
                if (line.isEmpty()) continue;
                try
                {
                    count += parseLine(line, incoming);
                }
                catch (Exception e)
                {
                    log.warn("Failed to parse line in {}: {}", logFile.getName(), e.getMessage());
                }
            }
        }
        catch (IOException e)
        {
            log.warn("Could not read loot log: {}", logFile.getAbsolutePath());
        }
        return count;
    }

    private int parseLine(String line, Map<String, List<DropRecord>> incoming)
    {
        JsonObject obj = new JsonParser().parse(line).getAsJsonObject();

        JsonElement sourceEl = obj.get("name");
        if (sourceEl == null || sourceEl.isJsonNull()) return 0;
        String source = sourceEl.getAsString();

        // Loot tracker records only carry a kill count when the KC chat
        // message was seen; treat missing KC as unknown (-1) rather than
        // discarding the drop entirely.
        JsonElement kcEl = obj.get("killCount");
        int killCount = (kcEl != null && !kcEl.isJsonNull()) ? kcEl.getAsInt() : -1;

        JsonElement dateEl = obj.get("date");
        long timestamp = (dateEl != null && !dateEl.isJsonNull())
            ? parseDate(dateEl.getAsString())
            : System.currentTimeMillis();

        JsonArray drops = obj.getAsJsonArray("drops");
        if (drops == null) return 0;

        int count = 0;
        for (JsonElement el : drops)
        {
            JsonElement itemEl = el.getAsJsonObject().get("name");
            if (itemEl == null || itemEl.isJsonNull()) continue;
            String itemName = itemEl.getAsString();
            if (itemName == null || itemName.isEmpty()) continue;

            String key = itemName.toLowerCase().trim();
            List<DropRecord> records = incoming.computeIfAbsent(key, k -> new ArrayList<>());
            boolean dup = records.stream().anyMatch(
                r -> r.getKillCount() == killCount && source.equalsIgnoreCase(r.getSource())
            );
            if (!dup)
            {
                records.add(new DropRecord(killCount, source, timestamp));
                count++;
            }
        }
        return count;
    }

    private long parseDate(String dateStr)
    {
        try
        {
            return LOG_DATE_FMT.parse(dateStr).getTime();
        }
        catch (ParseException e)
        {
            return System.currentTimeMillis();
        }
    }

    private void markDone()
    {
        IMPORTED_FLAG.getParentFile().mkdirs();
        try
        {
            IMPORTED_FLAG.createNewFile();
        }
        catch (IOException e)
        {
            log.warn("Could not write import flag", e);
        }
    }
}
