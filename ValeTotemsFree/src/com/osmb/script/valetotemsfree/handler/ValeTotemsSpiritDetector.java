package com.osmb.script.valetotemsfree.handler;

import com.osmb.api.shape.Rectangle;
import com.osmb.api.shape.Shape;
import com.osmb.api.utils.RandomUtils;
import com.osmb.api.visual.SearchablePixel;
import com.osmb.api.visual.color.ColorModel;
import com.osmb.api.visual.color.tolerance.ToleranceComparator;
import com.osmb.api.visual.color.tolerance.impl.SingleThresholdComparator;
import com.osmb.api.visual.ocr.fonts.Font;

import java.awt.Color;
import java.awt.Point;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ValeTotemsSpiritDetector {
    private static final int COLOR_TOLERANCE_HIGH = 60;
    private static final int COLOR_TOLERANCE_MEDIUM = 50;
    private static final int PIXEL_COUNT_THRESHOLD = 15;
    private static final int SPIRIT_TEXT_PADDING = 3;
    private static final int MIN_SPIRIT_TEXT_LENGTH = 4;
    private static final int ESTIMATED_FONT_HEIGHT = 6;
    private static final int SPIRIT_TEXT_COLOR = -16385800;
    private static final Map<String, Integer> SPIRIT_OPTION_MAP = Map.of("buffalo", 1, "jaguar", 2, "eagle", 3, "snake", 4, "scorpion", 5);
    private static final Set<String> VALID_SPIRITS = Set.of("jaguar", "buffalo", "snake", "eagle", "scorpion");

    private final ValeTotemsContext context;
    private final Set<String> detectedSpirits = new LinkedHashSet<>();
    private boolean spiritsReadyLogged = false;

    public ValeTotemsSpiritDetector(ValeTotemsContext context) {
        this.context = context;
    }

    public Set<String> getDetectedSpirits() {
        return detectedSpirits;
    }

    public void clearDetectedSpirits() {
        detectedSpirits.clear();
        spiritsReadyLogged = false;
    }

    public void scanForSpirits() {
        if (detectedSpirits.size() >= 3) return;

        List<String> detected = detectSpirits();
        if (detected.size() > 3) {
            // If we somehow detect more than 3, clear and rescan once
            detectedSpirits.clear();
            spiritsReadyLogged = false;
            detected = detectSpirits();
        }
        for (String spirit : detected) {
            if (detectedSpirits.size() >= 3) {
                break;
            }
            if (detectedSpirits.add(spirit)) {
                log("Spirit detected: " + spirit + " (" + detectedSpirits.size() + "/3)");
            }
        }
    }

    public void handleSpiritDialogue() {
        if (detectedSpirits.size() < 3) {
            spiritsReadyLogged = false;
            scanForSpirits();
        }
        if (detectedSpirits.size() >= 3) {
            if (!spiritsReadyLogged) {
                log("All 3 Spirits found: " + String.valueOf(detectedSpirits));
                log("Human delay before selecting...");
                spiritsReadyLogged = true;
            }
            context.getScript().pollFramesHuman(() -> true, RandomUtils.gaussianRandom(100, 800, 200, 200), false);
            for (String spirit : detectedSpirits) {
                if (context.getScript().getWidgetManager().getDialogue().isVisible()) {
                    Integer optionIndexObj = SPIRIT_OPTION_MAP.get(spirit.toLowerCase());
                    if (optionIndexObj == null) continue;
                    int optionIndex = optionIndexObj;
                    Rectangle rectangle = getSpiritOptionBounds(optionIndex);
                    if (rectangle == null || isSpiritOptionSelected(rectangle)) continue;
                    if (context.getScript().getFinger().tap(true, (Shape) rectangle)) {
                        context.getScript().pollFramesUntil(() -> isSpiritOptionSelected(rectangle), 1500, false, false);
                    }
                    context.getScript().pollFramesHuman(() -> true, RandomUtils.gaussianRandom(80, 400, 100, 100), false);
                    continue;
                }
                break;
            }
        } else {
            context.getScript().pollFramesHuman(() -> true, RandomUtils.gaussianRandom(80, 400, 100, 100), false);
        }
    }

    private List<String> detectSpirits() {
        // Close inventory if open so NPCs aren't hidden behind it
        if (context.getScript().getWidgetManager().getInventory() != null && context.getScript().getWidgetManager().getInventory().isVisible()) {
            context.getScript().getWidgetManager().getInventory().close();
            context.getScript().pollFramesHuman(() -> true, RandomUtils.gaussianRandom(50, 300, 80, 80), false);
        }

        LinkedHashSet<String> detected = new LinkedHashSet<>();
        SearchablePixel searchablePixel = new SearchablePixel(SPIRIT_TEXT_COLOR, (ToleranceComparator) new SingleThresholdComparator(COLOR_TOLERANCE_HIGH), ColorModel.RGB);
        List<Point> pixels = context.getScript().getPixelAnalyzer().findPixels((Shape) context.getScript().getScreen().getBounds(), new SearchablePixel[]{searchablePixel});
        if (pixels.isEmpty()) {
            return new ArrayList<>();
        }

        Rectangle screenBounds = context.getScript().getScreen().getBounds();
        for (Rectangle rectangle : groupPixelsIntoRectangles(pixels)) {
            // Calculate OCR bounds with padding
            int x = Math.max(0, rectangle.x - SPIRIT_TEXT_PADDING);
            int y = Math.max(0, rectangle.y - SPIRIT_TEXT_PADDING);
            int width = Math.min(screenBounds.width - x, rectangle.width + (SPIRIT_TEXT_PADDING * 2));
            int height = Math.min(screenBounds.height - y, rectangle.height + (SPIRIT_TEXT_PADDING * 2));

            if (width <= 0 || height <= 0) {
                continue;
            }

            Rectangle ocrBounds = new Rectangle(x, y, width, height);
            String text = context.getScript().getOCR().getText(Font.STANDARD_FONT_BOLD, ocrBounds, new int[]{SPIRIT_TEXT_COLOR});

            if (text == null || text.trim().isEmpty()) {
                continue;
            }

            String textLower = text.toLowerCase().trim();

            // Validate text looks like a spirit name (must contain "spirit" and be long enough)
            if (!textLower.contains("spirit") || textLower.length() < MIN_SPIRIT_TEXT_LENGTH) {
                continue;
            }

            // Check for valid spirit names
            for (String validSpirit : VALID_SPIRITS) {
                if (textLower.contains(validSpirit)) {
                    detected.add(validSpirit);
                    break; // Only one spirit per text block
                }
            }
        }
        return new ArrayList<>(detected);
    }

    private List<Rectangle> groupPixelsIntoRectangles(List<Point> pixels) {
        if (pixels.isEmpty()) {
            return new ArrayList<>();
        }

        List<Rectangle> rectangles = new ArrayList<>();
        // Calculate merge distance based on estimated font height (results in ~12px)
        int mergeDistance = Math.max(8, ESTIMATED_FONT_HEIGHT * 2);

        // Process pixels and merge nearby ones into rectangles
        for (Point point : pixels) {
            Rectangle newRect = new Rectangle(point.x, point.y, 1, 1);
            boolean merged = false;

            // Try to merge
            for (int i = 0; i < rectangles.size(); i++) {
                Rectangle existing = rectangles.get(i);

                int dx = Math.max(existing.x - point.x, point.x - (existing.x + existing.width));
                int dy = Math.max(existing.y - point.y, point.y - (existing.y + existing.height));

                if (dx <= mergeDistance && dy <= mergeDistance) {
                    rectangles.set(i, existing.union(newRect));
                    merged = true;
                    break;
                }
            }

            if (!merged) {
                rectangles.add(newRect);
            }
        }

        return rectangles;
    }

    private Rectangle getSpiritOptionBounds(int optionIndex) {
        Rectangle dialogueBounds = context.getScript().getWidgetManager().getDialogue().getBounds();
        if (dialogueBounds == null) {
            return null;
        }

        int columns = 5;
        double cellWidth = (double) dialogueBounds.width / columns;
        double iconRowY = dialogueBounds.y + (dialogueBounds.height * 0.45) + 9;

        double colCenter = dialogueBounds.x + (optionIndex - 0.5) * cellWidth;

        int boxSize = Math.min(44, (int) Math.round(cellWidth * 0.5));
        int half = boxSize / 2;

        int left = (int) Math.round(colCenter) - half;
        int top = (int) Math.round(iconRowY) - half;

        return new Rectangle(left, top, boxSize, boxSize);
    }

    private boolean isSpiritOptionSelected(Rectangle rectangle) {
        SearchablePixel searchablePixel = new SearchablePixel(new Color(180, 50, 50).getRGB(), (ToleranceComparator) new SingleThresholdComparator(COLOR_TOLERANCE_MEDIUM), ColorModel.RGB);
        return context.getScript().getPixelAnalyzer().findPixels((Shape) rectangle, new SearchablePixel[]{searchablePixel}).size() > PIXEL_COUNT_THRESHOLD;
    }

    private void log(String message) {
        context.getScript().log("ValeTotems", message);
    }
}
