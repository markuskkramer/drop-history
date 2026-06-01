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
}
