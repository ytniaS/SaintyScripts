package com.osmb.script.libation;

import com.osmb.api.ScriptCore;
import com.osmb.api.definition.SpriteDefinition;
import com.osmb.api.shape.Rectangle;
import com.osmb.api.ui.component.ComponentCentered;
import com.osmb.api.ui.component.ComponentImage;
import com.osmb.api.utils.RandomUtils;
import com.osmb.api.visual.color.ColorModel;
import com.osmb.api.visual.color.ColorUtils;
import com.osmb.api.visual.color.tolerance.ToleranceComparator;
import com.osmb.api.visual.drawing.BorderPalette;
import com.osmb.api.visual.drawing.Canvas;

import java.awt.*;

public class QuetzalMapInterface extends ComponentCentered {

    private static final Rectangle CLOSE_BUTTON = new Rectangle(462, 5, 21, 21);

    private static final Rectangle ALDARIN_RELATIVE_RECT = new Rectangle(273, 494, 27, 18);
    private static final Rectangle TEOMAT_RELATIVE_RECT = new Rectangle(284, 375, 19, 20);

    public QuetzalMapInterface(ScriptCore core) {
        super(core);
    }

    @Override
    protected ComponentImage buildBackgroundImage() {
        Canvas canvas = new Canvas(488, 300, ColorUtils.TRANSPARENT_PIXEL);
        canvas.fillRect(5, 5, canvas.canvasWidth - 10, canvas.canvasHeight - 10, ColorUtils.TRANSPARENT_PIXEL);
        canvas.fillRect(0, 23, canvas.canvasWidth, 17, ColorUtils.TRANSPARENT_PIXEL);
        drawBorderCorners(canvas, core, BorderPalette.STEEL_BORDER);
        return new ComponentImage<>(
                canvas.toSearchableImage(ToleranceComparator.ZERO_TOLERANCE, ColorModel.RGB),
                -1,
                1
        );
    }

    private void drawBorderCorners(Canvas canvas, ScriptCore core, BorderPalette palette) {
        canvas.setDrawArea(0, 0, canvas.canvasWidth, canvas.canvasHeight);
        SpriteDefinition topLeft = core.getSpriteManager().getSprite(palette.getTopLeftBorderID());
        SpriteDefinition topRight = core.getSpriteManager().getSprite(palette.getTopRightBorderID());
        SpriteDefinition bottomLeft = core.getSpriteManager().getSprite(palette.getBottomLeftBorderID());
        SpriteDefinition bottomRight = core.getSpriteManager().getSprite(palette.getBottomRightBorderID());

        drawClippedSprite(canvas, topLeft, 0, 0, 0, 0, topLeft.width - 6, topLeft.height - 6);
        int trDrawX = canvas.canvasWidth - topRight.width;
        drawClippedSprite(canvas, topRight, trDrawX + 6, 0, 6, 0, topRight.width - 6, topRight.height - 6);
        canvas.drawSpritePixels(bottomLeft, 0, canvas.canvasHeight - bottomLeft.height);
        canvas.drawSpritePixels(bottomRight, canvas.canvasWidth - bottomRight.width, canvas.canvasHeight - bottomRight.height);
    }

    private void drawClippedSprite(Canvas canvas, SpriteDefinition sprite, int destX, int destY,
                                   int srcX, int srcY, int clipWidth, int clipHeight) {
        int[] srcPixels = sprite.pixels;
        int canvasWidth = canvas.canvasWidth;
        int[] canvasPixels = canvas.pixels;
        for (int y = 0; y < clipHeight; y++) {
            for (int x = 0; x < clipWidth; x++) {
                int srcIndex = (srcY + y) * sprite.width + (srcX + x);
                int destIndex = (destY + y) * canvasWidth + (destX + x);
                int rgb = srcPixels[srcIndex];
                if (rgb != 0 && rgb != 0xFF00FF) {
                    canvasPixels[destIndex] = rgb;
                }
            }
        }
    }

    @Override
    public boolean isVisible() {
        return getBounds() != null;
    }

    //close not currently used but perhaps for future scripts if Quetzalmap needs reusing
    public boolean close() {
        Rectangle bounds = getBounds();
        if (bounds == null) {
            return false;
        }
        Rectangle closeRect = bounds.getSubRectangle(CLOSE_BUTTON);
        boolean tapped = core.getFinger().tap(closeRect);
        if (!tapped) {
            return false;
        }
        int timeout = RandomUtils.weightedRandom(2500, 6000, 0.0017);
        return core.pollFramesHuman(() -> !isVisible(), timeout);
    }


    public Point getAldarinClickPoint() {
        return randomPointInRelativeRect(ALDARIN_RELATIVE_RECT);
    }

    public Point getTeomatClickPoint() {
        return randomPointInRelativeRect(TEOMAT_RELATIVE_RECT);
    }

    private Point randomPointInRelativeRect(Rectangle relativeRect) {
        Rectangle bounds = getBounds();
        if (bounds == null) {
            return null;
        }
        int x = bounds.x + relativeRect.x + RandomUtils.uniformRandom(0, relativeRect.width);
        int y = bounds.y + relativeRect.y + RandomUtils.uniformRandom(0, relativeRect.height);
        return new Point(x, y);
    }
}
