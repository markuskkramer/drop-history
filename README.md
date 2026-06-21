# Drop History

A RuneLite plugin that tracks what kill count you received each collection log item at, and shows a tooltip when hovering items in your collection log.

## Features

- **KC tooltip** — hover any item in your collection log to see every KC you received it at, along with the boss name
- **Optional date display** — toggle timestamps in the tooltip via plugin settings
- **Historical import** — on first run, automatically imports your existing loot tracker data so your history is populated from day one
- **Local storage** — all data is stored locally in `~/.runelite/drop-history/drops.json`

## Historical import — what it can and can't recover

On startup, Drop History backfills your history from every local source it
can find, preferring the most accurate data available for each drop:

1. **Loot tracker logs** (`~/.runelite/loots/`) — exact KCs, written by the
   Loot Logger plugin if you ever ran it.
2. **Boss kill screenshots** (`~/.runelite/screenshots/<name>/Boss Kills/`) —
   exact KCs parsed from filenames like `Vorkath(847).png`, matched to
   collection log screenshots taken at the same moment.
3. **Collection log screenshots**
   (`~/.runelite/screenshots/<name>/Collection Log/`) — RuneLite saves these
   **by default**, giving the item and the date it was obtained. When only
   the date is known, the plugin can estimate your KC at that date from your
   [Wise Old Man](https://wiseoldman.net) boss KC history; estimates are
   shown as `KC ~847 (est.)`.
4. Anything else appears as "KC unknown".

Imports retry on every startup until data is found, so installing the plugin
before the data exists is fine. Drops received before any of these sources
recorded them cannot be recovered — that information was never stored.

Wise Old Man estimation is **off by default** and only works if your account
has snapshot history there (anyone who has ever been looked up on
wiseoldman.net). Enabling it shows a warning because it sends your display
name and IP address to the third-party Wise Old Man API.

## Example

Hovering "Twisted bow" in your collection log might show:

> **Drop History: Twisted bow**
> KC 264 — Chambers of Xeric
> KC 891 — Chambers of Xeric

## Configuration

| Setting | Default | Description |
|---|---|---|
| Show KC tooltip | On | Show drop history when hovering collection log items |
| Show date in tooltip | Off | Include the date alongside the KC |
| Max drops listed per item | 25 | Items with more drops than this show a summary (total + first KC) instead of every drop |
| Estimate KCs via Wise Old Man | Off | Estimate unknown KCs from your Wise Old Man history (sends your display name and IP to the WOM API; shows a warning when enabled) |
