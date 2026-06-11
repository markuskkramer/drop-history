package com.drophistory;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Estimates the kill count for drops where only the date is known, by
 * interpolating the player's historical boss KC from Wise Old Man snapshots.
 *
 * Requests are made lazily when an unknown-KC record is displayed, at most
 * once per record per session, and successful estimates are persisted so
 * they are never requested again.
 */
@Slf4j
@Singleton
public class WomKcEstimator
{
    private static final String WOM_API_BASE = "https://api.wiseoldman.net/v2";
    private static final String USER_AGENT = "drop-history RuneLite plugin (github.com/markuskkramer/drop-history)";

    /** How far around the drop date to look for KC snapshots. */
    private static final long SNAPSHOT_WINDOW_MS = 180L * 24 * 60 * 60 * 1000;

    /**
     * Collection log page names that don't map to a WOM boss metric by
     * simple normalization.
     */
    private static final Map<String, String> METRIC_ALIASES = new HashMap<>();

    static
    {
        METRIC_ALIASES.put("the nightmare", "nightmare");
        METRIC_ALIASES.put("the mimic", "mimic");
        METRIC_ALIASES.put("the fight caves", "tztok_jad");
        METRIC_ALIASES.put("the inferno", "tzkal_zuk");
        METRIC_ALIASES.put("moons of peril", "lunar_chests");
        METRIC_ALIASES.put("fortis colosseum", "sol_heredit");
        METRIC_ALIASES.put("royal titans", "the_royal_titans");
    }

    private static final Type TIMELINE_TYPE = new TypeToken<List<TimelinePoint>>(){}.getType();

    private static class TimelinePoint
    {
        long value;
        String date;
    }

    @Inject private OkHttpClient httpClient;
    @Inject private Gson gson;
    @Inject private DropHistoryManager manager;
    @Inject private DropHistoryConfig config;

    /** Records with a request in flight or already attempted this session. */
    private final Set<String> attempted = ConcurrentHashMap.newKeySet();

    /**
     * Asynchronously estimates the KC for a record with an unknown kill
     * count. Safe to call every frame; only the first call per record does
     * anything. On success the record is updated and persisted via
     * {@link DropHistoryManager#applyEstimate}.
     *
     * @param itemName item the record belongs to
     * @param record   the record with killCount == -1 and a usable timestamp
     * @param bossName collection log page title, used as the WOM boss metric
     * @param playerName the local player's display name
     */
    public void requestEstimate(String itemName, DropRecord record, String bossName, String playerName)
    {
        if (!config.womEstimates()
            || itemName == null || bossName == null || playerName == null
            || record.getKillCount() != -1 || record.getTimestamp() <= 0)
        {
            return;
        }

        String metric = toMetric(bossName);
        if (metric == null)
        {
            return;
        }

        String key = itemName.toLowerCase() + "|" + record.getTimestamp();
        if (!attempted.add(key))
        {
            return;
        }

        log.info("Requesting WOM KC estimate for {} ({}) as {}", itemName, metric, playerName);

        long dropTime = record.getTimestamp();
        HttpUrl url = HttpUrl.parse(WOM_API_BASE).newBuilder()
            .addPathSegment("players")
            .addPathSegment(playerName)
            .addPathSegment("snapshots")
            .addPathSegment("timeline")
            .addQueryParameter("metric", metric)
            .addQueryParameter("startDate", Instant.ofEpochMilli(dropTime - SNAPSHOT_WINDOW_MS).toString())
            .addQueryParameter("endDate", Instant.ofEpochMilli(dropTime + SNAPSHOT_WINDOW_MS).toString())
            .build();

        Request request = new Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build();

        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.info("WOM request failed for {}: {}", metric, e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                try (ResponseBody body = response.body())
                {
                    if (!response.isSuccessful() || body == null)
                    {
                        log.info("WOM returned {} for {} ({})", response.code(), playerName, metric);
                        return;
                    }
                    List<TimelinePoint> timeline = gson.fromJson(body.charStream(), TIMELINE_TYPE);
                    int estimate = interpolate(timeline, dropTime);
                    if (estimate > 0)
                    {
                        manager.applyEstimate(itemName, dropTime, estimate, bossName);
                        log.info("Estimated KC {} for {} via WOM ({})", estimate, itemName, metric);
                    }
                    else
                    {
                        log.info("No usable WOM snapshots for {} ({}) around the drop date", playerName, metric);
                    }
                }
                catch (Exception e)
                {
                    log.info("Failed to parse WOM response for {}", metric, e);
                }
            }
        });
    }

    /**
     * Linearly interpolates the KC at dropTime from the snapshots around it.
     * Returns -1 when no usable snapshots exist.
     */
    static int interpolate(List<TimelinePoint> timeline, long dropTime)
    {
        if (timeline == null || timeline.isEmpty())
        {
            return -1;
        }

        TimelinePoint before = null;
        TimelinePoint after = null;
        long beforeTime = 0;
        long afterTime = 0;

        for (TimelinePoint point : timeline)
        {
            if (point.value <= 0 || point.date == null)
            {
                continue;
            }
            long t;
            try
            {
                t = Instant.parse(point.date).toEpochMilli();
            }
            catch (Exception e)
            {
                continue;
            }
            if (t <= dropTime && (before == null || t > beforeTime))
            {
                before = point;
                beforeTime = t;
            }
            if (t >= dropTime && (after == null || t < afterTime))
            {
                after = point;
                afterTime = t;
            }
        }

        if (before != null && after != null)
        {
            if (afterTime == beforeTime)
            {
                return (int) before.value;
            }
            double fraction = (double) (dropTime - beforeTime) / (afterTime - beforeTime);
            return (int) Math.round(before.value + fraction * (after.value - before.value));
        }
        if (before != null)
        {
            return (int) before.value;
        }
        if (after != null)
        {
            return (int) after.value;
        }
        return -1;
    }

    /**
     * Maps a collection log page title to a WOM boss metric name, or null
     * if no mapping is likely. Unknown metrics fail harmlessly server-side.
     */
    static String toMetric(String bossName)
    {
        String name = bossName.toLowerCase().trim();
        String alias = METRIC_ALIASES.get(name);
        if (alias != null)
        {
            return alias;
        }
        String metric = name
            .replace("'", "")
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
        return metric.isEmpty() ? null : metric;
    }
}
