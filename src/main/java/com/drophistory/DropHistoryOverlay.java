package com.drophistory;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;

import javax.inject.Inject;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Renders a KC history tooltip when hovering an item in the collection log.
 * Uses InterfaceID.Collection.ITEMS_CONTENTS to locate the item grid widget.
 * Each dynamic child of that container is an item slot.
 */
@Slf4j
public class DropHistoryOverlay extends Overlay
{
    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("MMM d, yyyy");

    @Inject private Client client;
    @Inject private DropHistoryConfig config;
    @Inject private DropHistoryManager manager;
    @Inject private TooltipManager tooltipManager;
    @Inject private WomKcEstimator estimator;

    @Inject
    public DropHistoryOverlay()
    {
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!config.showTooltip())
        {
            return null;
        }

        Widget container = client.getWidget(InterfaceID.Collection.ITEMS_CONTENTS);
        if (container == null || container.isHidden())
        {
            return null;
        }

        Widget[] items = container.getDynamicChildren();
        if (items == null)
        {
            return null;
        }

        Point mouse = client.getMouseCanvasPosition();
        if (mouse == null)
        {
            return null;
        }

        for (Widget item : items)
        {
            if (item == null || item.isHidden() || item.getItemId() == -1)
            {
                continue;
            }

            if (!item.getBounds().contains(mouse.getX(), mouse.getY()))
            {
                continue;
            }

            // Resolve item name — strip color tags
            String itemName = item.getName();
            if (itemName == null || itemName.isEmpty())
            {
                itemName = client.getItemDefinition(item.getItemId()).getName();
            }
            itemName = itemName.replaceAll("<[^>]+>", "").trim();

            if (itemName.isEmpty())
            {
                break;
            }

            List<DropRecord> drops = manager.getDrops(itemName);

            StringBuilder sb = new StringBuilder();
            sb.append("<col=ff9040>Drop History: ").append(itemName).append("</col>");

            if (drops.isEmpty())
            {
                sb.append("<br><col=aaaaaa>No drops recorded yet</col>");
            }
            else if (drops.size() > config.maxDropsShown())
            {
                // Bulk items (Demon tears, Tokkul, ...) can have thousands
                // of recorded drops; show a summary instead of every line.
                sb.append("<br><col=aaaaaa>").append(String.format("%,d", drops.size()))
                  .append(" drops recorded</col>");
                int firstKc = drops.stream()
                    .mapToInt(DropRecord::getKillCount)
                    .filter(kc -> kc > 0)
                    .min()
                    .orElse(-1);
                if (firstKc > 0)
                {
                    sb.append("<br><col=ffffff>First at KC ").append(String.format("%,d", firstKc)).append("</col>");
                }
            }
            else
            {
                String pageTitle = collectionLogPageTitle();
                String playerName = client.getLocalPlayer() != null
                    ? client.getLocalPlayer().getName()
                    : null;

                for (DropRecord record : drops)
                {
                    if (record.getKillCount() == -1)
                    {
                        // Kick off an async WOM estimate; no-op after the first call.
                        estimator.requestEstimate(itemName, record, pageTitle, playerName);
                    }

                    sb.append("<br>");
                    if (record.getKillCount() == -1)
                    {
                        sb.append("<col=aaaaaa>KC unknown</col>");
                    }
                    else if (record.isEstimated())
                    {
                        sb.append("<col=ffffff>KC ~").append(String.format("%,d", record.getKillCount())).append("</col>")
                          .append(" <col=aaaaaa>(est.)</col>");
                    }
                    else
                    {
                        sb.append("<col=ffffff>KC ").append(String.format("%,d", record.getKillCount())).append("</col>");
                    }

                    String source = record.getSource();
                    if (source != null && !source.isEmpty() && !"Unknown".equalsIgnoreCase(source))
                    {
                        sb.append(" <col=ff9040>— ").append(source).append("</col>");
                    }

                    if (config.showTimestamp())
                    {
                        sb.append(" <col=888888>(")
                          .append(DATE_FMT.format(new Date(record.getTimestamp())))
                          .append(")</col>");
                    }
                }
            }

            tooltipManager.add(new Tooltip(sb.toString()));
            break;
        }

        return null;
    }

    /**
     * The title of the currently open collection log page (e.g. "Vorkath"),
     * which identifies the boss for KC estimation. Null when unavailable.
     */
    private String collectionLogPageTitle()
    {
        Widget header = client.getWidget(InterfaceID.Collection.HEADER_TEXT);
        if (header == null)
        {
            return null;
        }
        Widget[] children = header.getDynamicChildren();
        if (children == null || children.length == 0 || children[0].getText() == null)
        {
            return null;
        }
        String title = children[0].getText().replaceAll("<[^>]+>", "").trim();
        return title.isEmpty() ? null : title;
    }
}
