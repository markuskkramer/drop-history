# Drop History

A RuneLite plugin that tracks what kill count you received each collection log item at, and shows a tooltip when hovering items in your collection log.

## Features

- **KC tooltip** — hover any item in your collection log to see every KC you received it at, along with the boss name
- **Optional date display** — toggle timestamps in the tooltip via plugin settings
- **Historical import** — on first run, automatically imports your existing loot tracker data so your history is populated from day one
- **Local storage** — all data is stored locally in `~/.runelite/drop-history/drops.json`

## Historical import — what it can and can't recover

The historical import reads the RuneLite **Loot Tracker** plugin's locally saved
data from `~/.runelite/loots/`. That folder only contains data if the Loot
Tracker has been saving loot to disk on your machine.

- If you have local loot tracker data, your past drops (and their KCs, where
  the loot tracker recorded them) are imported automatically.
- If you don't, there is nothing on disk to import — Drop History will simply
  start recording from your next kill onward. The plugin re-checks for loot
  tracker data on every startup until it finds some.
- Drops the loot tracker saved without a kill count appear as "KC unknown".
- Drops received before you ever used the loot tracker cannot be recovered —
  that information was never stored anywhere.

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
