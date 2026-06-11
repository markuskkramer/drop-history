package com.drophistory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single recorded drop of an item.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class DropRecord
{
    /** Kill count at time of drop. -1 if KC was unknown. */
    private int killCount;

    /** The boss or source name (e.g. "Zulrah"). */
    private String source;

    /** Unix timestamp (ms) of when the drop was received. */
    private long timestamp;

    /**
     * True when the kill count was estimated (e.g. interpolated from
     * Wise Old Man snapshots) rather than observed directly.
     * Absent in older saved data, which Gson deserializes as false.
     */
    private boolean estimated;

    public DropRecord(int killCount, String source, long timestamp)
    {
        this(killCount, source, timestamp, false);
    }
}
