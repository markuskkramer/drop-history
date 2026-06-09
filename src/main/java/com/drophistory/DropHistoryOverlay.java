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
            else
            {
                for (DropRecord record : drops)
                {
                    sb.append("<br>");
                    if (record.getKillCount() == -1)
                    {
                        sb.append("<col=aaaaaa>KC unknown</col>");
                    }
                    else
                    {
                        sb.append("<col=ffffff>KC ").append(String.format("%,d", record.getKillCount())).append("</col>");
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
}
