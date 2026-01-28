package com.osmb.script.babayaga;

import com.osmb.api.ScriptCore;
import com.osmb.api.item.ItemGroup;
import com.osmb.api.shape.Rectangle;
import com.osmb.api.ui.SpriteID;
import com.osmb.api.ui.component.ComponentCentered;
import com.osmb.api.ui.component.ComponentImage;
import com.osmb.api.utils.RandomUtils;
import com.osmb.api.utils.UIResult;
import com.osmb.api.visual.color.ColorModel;
import com.osmb.api.visual.color.ColorUtils;
import com.osmb.api.visual.color.tolerance.ToleranceComparator;
import com.osmb.api.visual.drawing.BorderPalette;
import com.osmb.api.visual.drawing.Canvas;
import com.osmb.api.visual.ocr.fonts.Font;

import java.awt.*;
import java.util.List;

public class RuneShopInterface extends ComponentCentered implements ItemGroup {


    private static final Rectangle TITLE_BOUNDS = new Rectangle(6, 6, 476, 23);
    private static final Rectangle CLOSE_BUTTON = new Rectangle(460, 7, 21, 21);

    private static final int BUTTON_PADDING = 3;

    private static final String SHOP_NAME_PART1 = "baba yaga";
    private static final String SHOP_NAME_PART2 = "magic shop";

    public RuneShopInterface(ScriptCore core) {
        super(core);
    }

    @Override
    protected ComponentImage buildBackgroundImage() {
        Canvas c = new Canvas(488, 300, ColorUtils.TRANSPARENT_PIXEL);
        c.createBackground(core, BorderPalette.STEEL_BORDER, null);
        c.fillRect(5, 5, c.canvasWidth - 10, c.canvasHeight - 10, ColorUtils.TRANSPARENT_PIXEL);

        return new ComponentImage<>(
                c.toSearchableImage(ToleranceComparator.ZERO_TOLERANCE, ColorModel.RGB),
                -1,
                1
        );
    }

    @Override
    public boolean isVisible() {
        Rectangle b = getBounds();
        if (b == null) {
            return false;
        }

        // Read shop title
        String title = core.getOCR().getText(
                Font.STANDARD_FONT_BOLD,
                b.getSubRectangle(TITLE_BOUNDS),
                ColorUtils.ORANGE_UI_TEXT
        );

        if (title == null) {
            return false;
        }

        title = title.trim().toLowerCase();
        return title.contains(SHOP_NAME_PART1) && title.contains(SHOP_NAME_PART2);
    }


    public void close() {
        Rectangle b = getBounds();
        if (b == null) {
            return;
        }

        core.getFinger().tap(b.getSubRectangle(CLOSE_BUTTON));
        pollFramesUntil(() -> !isVisible(), RandomUtils.uniformRandom(1800, 2200));
    }


    public UIResult<Integer> getSelectedAmount() {
        Rectangle bounds = getBounds();
        if (bounds == null) {
            return UIResult.notVisible();
        }

        // Find red button (indicates selected amount)
        List<Rectangle> redButtons = core.getImageAnalyzer().findContainers(
                bounds,
                SpriteID.BANK_TEXT_BUTTON_RED_NW,
                SpriteID.BANK_TEXT_BUTTON_RED_NE,
                SpriteID.BANK_TEXT_BUTTON_RED_SW,
                SpriteID.BANK_TEXT_BUTTON_RED_SE
        );

        if (redButtons.isEmpty()) {
            return UIResult.notVisible();
        }

        // Read amount from button
        String text = core.getOCR()
                .getText(Font.STANDARD_FONT, redButtons.get(0), ColorUtils.ORANGE_UI_TEXT)
                .replaceAll("[^0-9]", "");

        if (text.isEmpty()) {
            return UIResult.of(null);
        }

        return UIResult.of(Integer.parseInt(text));
    }

    public boolean setSelectedAmount(int amount) {
        Rectangle bounds = getBounds();
        if (bounds == null) {
            return false;
        }

        // Check if already selected
        UIResult<Integer> selected = getSelectedAmount();
        if (!selected.isNotVisible() && selected.get() != null && selected.get() == amount) {
            return true;
        }

        // Find all amount buttons
        List<Rectangle> buttons = core.getImageAnalyzer().findContainers(
                bounds,
                SpriteID.BANK_TEXT_BUTTON_NW,
                SpriteID.BANK_TEXT_BUTTON_NE,
                SpriteID.BANK_TEXT_BUTTON_SW,
                SpriteID.BANK_TEXT_BUTTON_SE
        );

        if (buttons.isEmpty()) {
            return false;
        }

        // Find and click the button with the target amount
        for (Rectangle btn : buttons) {
            String text = core.getOCR()
                    .getText(Font.STANDARD_FONT, btn, ColorUtils.ORANGE_UI_TEXT)
                    .replaceAll("[^0-9]", "");

            if (text.isEmpty()) {
                continue;
            }

            int value = Integer.parseInt(text);
            if (value == amount) {
                // Tap button (has built-in humanized delay)
                core.getFinger().tap(btn.getPadding(BUTTON_PADDING));

                // Wait for amount to update
                return pollFramesUntil(() -> {
                    UIResult<Integer> now = getSelectedAmount();
                    return !now.isNotVisible() && now.get() != null && now.get() == amount;
                }, RandomUtils.uniformRandom(1300, 1700));
            }
        }

        return false;
    }


    @Override
    public Point getStartPoint() {
        Rectangle bounds = getBounds();
        if (bounds == null) {
            return null;
        }
        return new Point(bounds.x + 61, bounds.y + 40);
    }

    @Override
    public int groupWidth() {
        return 8;
    }

    @Override
    public int groupHeight() {
        return 4;
    }

    @Override
    public int xIncrement() {
        return 47;
    }

    @Override
    public int yIncrement() {
        return 47;
    }

    @Override
    public Rectangle getGroupBounds() {
        return getBounds();
    }

    @Override
    public ScriptCore getCore() {
        return core;
    }


    private boolean pollFramesUntil(java.util.function.BooleanSupplier condition, int timeout) {
        return core.pollFramesUntil(condition, timeout);
    }
}