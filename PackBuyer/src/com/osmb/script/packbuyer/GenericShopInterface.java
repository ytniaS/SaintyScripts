package com.osmb.script.packbuyer;

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

public class GenericShopInterface extends ComponentCentered implements ItemGroup {
    private static final Rectangle TITLE_BOUNDS = new Rectangle(6, 6, 476, 23);
    private static final Rectangle CLOSE_BUTTON = new Rectangle(460, 7, 21, 21);
    private static final int BUTTON_PADDING = 3;

    private final String expectedTitleLower;

    public GenericShopInterface(ScriptCore core, String expectedTitle) {
        super(core);
        this.expectedTitleLower = expectedTitle == null ? "" : expectedTitle.trim().toLowerCase();
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

        String title = core.getOCR().getText(
                Font.STANDARD_FONT_BOLD,
                b.getSubRectangle(TITLE_BOUNDS),
                ColorUtils.ORANGE_UI_TEXT
        );

        if (title == null) {
            return false;
        }

        String t = title.trim().toLowerCase();
        return !expectedTitleLower.isEmpty() && t.contains(expectedTitleLower.toLowerCase());
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

        String t = core.getOCR()
                .getText(Font.STANDARD_FONT, redButtons.get(0), ColorUtils.ORANGE_UI_TEXT)
                .replaceAll("[^0-9]", "");

        if (t.isEmpty()) {
            return UIResult.of(null);
        }

        return UIResult.of(Integer.parseInt(t));
    }

    public boolean setSelectedAmount(int amount) {
        Rectangle bounds = getBounds();
        if (bounds == null) {
            return false;
        }

        UIResult<Integer> selected = getSelectedAmount();
        if (!selected.isNotVisible() && selected.get() != null && selected.get() == amount) {
            return true;
        }

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

        for (Rectangle btn : buttons) {
            String t = core.getOCR()
                    .getText(Font.STANDARD_FONT, btn, ColorUtils.ORANGE_UI_TEXT)
                    .replaceAll("[^0-9]", "");

            if (t.isEmpty()) {
                continue;
            }

            int v = Integer.parseInt(t);
            if (v == amount) {
                core.getFinger().tap(btn.getPadding(BUTTON_PADDING));
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