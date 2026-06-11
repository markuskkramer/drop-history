package com.drophistory;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.config.RuneScapeProfileType;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-time importer that backfills drop history from RuneLite's screenshot
 * folders.
 *
 * The core Screenshot plugin saves "Collection log (Item name).png" by
 * default whenever a collection log entry is completed, which gives us the
 * item and the date it was obtained. If the player also had boss kill
 * screenshots enabled, files like "Vorkath(847).png" taken at the same
 * moment give us the exact kill count. Otherwise the record is imported
 * with an unknown KC, which the Wise Old Man estimator may fill in later.
 *
 * Like LootTrackerImporter, this only marks itself complete after importing
 * at least one record, so it retries on startup until data appears.
 */
@Slf4j
@Singleton
public class ScreenshotImporter
{
    private static final File IMPORTED_FLAG = new File(RuneLite.RUNELITE_DIR, "drop-history/screenshots-imported.flag");

    // "Collection log (Dragon warhammer).png" with an optional trailing timestamp
    private static final Pattern COLLECTION_LOG_FILE =
        Pattern.compile("^Collection log \\((.+)\\)(?: (\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}))?\\.png$");

    // "Vorkath(847).png" with an optional trailing timestamp
    private static final Pattern BOSS_KILL_FILE =
        Pattern.compile("^(.+?)\\((\\d+)\\)(?: (\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}))?\\.png$");

    private static final SimpleDateFormat FILE_TS_FMT = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");

    /** A boss kill screenshot within this window of a collection log screenshot is the same kill. */
    private static final long KILL_MATCH_WINDOW_MS = 2 * 60_000L;

    /** Skip a screenshot record when an existing record for the item is within this window. */
    private static final long DUPLICATE_WINDOW_MS = 10 * 60_000L;

    @Inject private DropHistoryManager manager;

    private static class BossKillShot
    {
        final String boss;
        final int killCount;
        final long timestamp;

        BossKillShot(String boss, int killCount, long timestamp)
        {
            this.boss = boss;
            this.killCount = killCount;
            this.timestamp = timestamp;
        }
    }

    public void runIfNeeded()
    {
        if (IMPORTED_FLAG.exists())
        {
            log.debug("Screenshot import already done, skipping");
            return;
        }

        File screenshotsDir = new File(RuneLite.RUNELITE_DIR, "screenshots");
        if (!screenshotsDir.exists())
        {
            log.info("No screenshots directory found at {} — screenshot import skipped, will retry next startup", screenshotsDir);
            return;
        }

        // Screenshots live either directly under screenshots/ (legacy) or in
        // one subdirectory per player. Match boss kills to collection log
        // entries within the same directory only. Directories for limited
        // time modes (leagues, deadman, ...) carry a "-<Profile Type>"
        // suffix and are skipped — only main game drops are imported.
        List<File> playerDirs = new ArrayList<>();
        playerDirs.add(screenshotsDir);
        File[] subDirs = screenshotsDir.listFiles(File::isDirectory);
        if (subDirs != null)
        {
            for (File d : subDirs)
            {
                if (!isSpecialModeDir(d.getName()))
                {
                    playerDirs.add(d);
                }
            }
        }

        Map<String, List<DropRecord>> incoming = new HashMap<>();
        int totalRecords = 0;

        for (File playerDir : playerDirs)
        {
            totalRecords += importPlayerDir(playerDir, incoming);
        }

        if (totalRecords == 0)
        {
            log.info("No importable collection log screenshots found — will retry next startup");
            return;
        }

        manager.bulkMerge(incoming);
        log.info("Screenshot import complete — {} drop records imported", totalRecords);
        markDone();
    }

    private int importPlayerDir(File playerDir, Map<String, List<DropRecord>> incoming)
    {
        File[] collectionShots = listPngs(new File(playerDir, "Collection Log"));
        if (collectionShots == null || collectionShots.length == 0)
        {
            return 0;
        }

        List<BossKillShot> bossKills = parseBossKills(listPngs(new File(playerDir, "Boss Kills")));

        int count = 0;
        for (File shot : collectionShots)
        {
            Matcher m = COLLECTION_LOG_FILE.matcher(shot.getName());
            if (!m.matches())
            {
                continue;
            }

            String itemName = m.group(1).trim();
            long timestamp = fileTimestamp(shot, m.group(2));

            if (hasNearbyRecord(manager.getDrops(itemName), timestamp)
                || hasNearbyRecord(incoming.get(itemName.toLowerCase().trim()), timestamp))
            {
                continue;
            }

            BossKillShot kill = findMatchingKill(bossKills, timestamp);
            DropRecord record = kill != null
                ? new DropRecord(kill.killCount, kill.boss, timestamp)
                : new DropRecord(-1, "Unknown", timestamp);

            incoming.computeIfAbsent(itemName.toLowerCase().trim(), k -> new ArrayList<>()).add(record);
            count++;
        }
        return count;
    }

    /**
     * True when a player screenshot directory belongs to a limited time
     * game mode. The Screenshot plugin appends "-<Profile Type>" to the
     * player name for non-standard worlds, e.g. "name-Deadman" or
     * "name-Trailblazer League"; the suffixes are derived from the client's
     * own profile type enum so new league names are covered automatically.
     */
    static boolean isSpecialModeDir(String dirName)
    {
        String lower = dirName.toLowerCase();
        for (RuneScapeProfileType type : RuneScapeProfileType.values())
        {
            if (type == RuneScapeProfileType.STANDARD)
            {
                continue;
            }
            String suffix = "-" + type.name().toLowerCase().replace('_', ' ');
            if (lower.endsWith(suffix))
            {
                return true;
            }
        }
        return false;
    }

    private static File[] listPngs(File dir)
    {
        if (!dir.isDirectory())
        {
            return null;
        }
        return dir.listFiles(f -> f.isFile() && f.getName().endsWith(".png"));
    }

    private static List<BossKillShot> parseBossKills(File[] files)
    {
        List<BossKillShot> kills = new ArrayList<>();
        if (files == null)
        {
            return kills;
        }
        for (File f : files)
        {
            Matcher m = BOSS_KILL_FILE.matcher(f.getName());
            if (!m.matches())
            {
                continue;
            }
            try
            {
                kills.add(new BossKillShot(m.group(1).trim(), Integer.parseInt(m.group(2)), fileTimestamp(f, m.group(3))));
            }
            catch (NumberFormatException e)
            {
                // kill count too large to be real; skip
            }
        }
        return kills;
    }

    private static BossKillShot findMatchingKill(List<BossKillShot> kills, long timestamp)
    {
        BossKillShot best = null;
        long bestDelta = KILL_MATCH_WINDOW_MS + 1;
        for (BossKillShot kill : kills)
        {
            long delta = Math.abs(kill.timestamp - timestamp);
            if (delta <= KILL_MATCH_WINDOW_MS && delta < bestDelta)
            {
                best = kill;
                bestDelta = delta;
            }
        }
        return best;
    }

    private static boolean hasNearbyRecord(List<DropRecord> records, long timestamp)
    {
        if (records == null)
        {
            return false;
        }
        return records.stream().anyMatch(r -> Math.abs(r.getTimestamp() - timestamp) <= DUPLICATE_WINDOW_MS);
    }

    /** Prefer the timestamp embedded in the filename; fall back to file mtime. */
    private static long fileTimestamp(File file, String nameTimestamp)
    {
        if (nameTimestamp != null)
        {
            try
            {
                return FILE_TS_FMT.parse(nameTimestamp).getTime();
            }
            catch (ParseException e)
            {
                // fall through to mtime
            }
        }
        return file.lastModified();
    }

    private void markDone()
    {
        IMPORTED_FLAG.getParentFile().mkdirs();
        try
        {
            if (!IMPORTED_FLAG.createNewFile())
            {
                log.debug("Import flag already existed");
            }
        }
        catch (java.io.IOException e)
        {
            log.warn("Could not write screenshot import flag", e);
        }
    }
}
