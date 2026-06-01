# Drop History

A RuneLite plugin that tracks what kill count you received each collection log item at, and shows a tooltip when hovering items in your collection log.

## Features

- **KC tooltip** — hover any item in your collection log to see every KC you received it at, along with the boss name
- **Optional date display** — toggle timestamps in the tooltip via plugin settings
- **Historical import** — on first run, automatically imports your existing loot tracker data so your history is populated from day one
- **Local storage** — all data is stored locally in `~/.runelite/drop-history/drops.json`

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
