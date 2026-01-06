package com.osmb.script.babayaga;

import com.osmb.api.ScriptCore;
import com.osmb.api.item.ItemGroup;
import com.osmb.api.shape.Rectangle;
import com.osmb.api.ui.SpriteID;
import com.osmb.api.ui.component.ComponentCentered;
import com.osmb.api.ui.component.ComponentImage;
import com.osmb.api.utils.UIResult;
import com.osmb.api.visual.color.ColorModel;
import com.osmb.api.visual.color.ColorUtils;
import com.osmb.api.visual.color.tolerance.ToleranceComparator;
import com.osmb.api.visual.drawing.BorderPalette;
import com.osmb.api.visual.drawing.Canvas;
import com.osmb.api.visual.ocr.fonts.Font;

import java.awt.Point;
import java.util.List;

public class RuneShopInterface extends ComponentCentered implements ItemGroup {
	
	private static final Rectangle TITLE_BOUNDS = new Rectangle(6, 6, 476, 23);
	private static final Rectangle CLOSE_BUTTON = new Rectangle(460, 7, 21, 21);
	
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
		if (b == null) return false;
		
		String title = core.getOCR().getText(
				Font.STANDARD_FONT_BOLD,
				b.getSubRectangle(TITLE_BOUNDS),
				ColorUtils.ORANGE_UI_TEXT
		                                    );
		
		if (title == null) return false;
		title = title.trim().toLowerCase();
		

		return title.contains("baba yaga") && title.contains("magic shop");
	}
	
	public void close() {
		Rectangle b = getBounds();
		if (b == null) return;
		
		core.getFinger().tap(b.getSubRectangle(CLOSE_BUTTON));
		core.submitTask(() -> !isVisible(), 2000);
	}


	
	public UIResult<Integer> getSelectedAmount() {
		Rectangle bounds = getBounds();
		if (bounds == null) return UIResult.notVisible();
		
		List<Rectangle> redButtons = core.getImageAnalyzer().findContainers(
				bounds,
				SpriteID.BANK_TEXT_BUTTON_RED_NW,
				SpriteID.BANK_TEXT_BUTTON_RED_NE,
				SpriteID.BANK_TEXT_BUTTON_RED_SW,
				SpriteID.BANK_TEXT_BUTTON_RED_SE
		                                                                   );
		
		if (redButtons.isEmpty())
			return UIResult.notVisible();
		
		String t = core.getOCR()
				.getText(Font.STANDARD_FONT, redButtons.get(0), ColorUtils.ORANGE_UI_TEXT)
				.replaceAll("[^0-9]", "");
		
		if (t.isEmpty())
			return UIResult.of(null);
		
		return UIResult.of(Integer.parseInt(t));
	}
	
	public boolean setSelectedAmount(int amount) {
		Rectangle bounds = getBounds();
		if (bounds == null) return false;
		
		UIResult<Integer> selected = getSelectedAmount();
		if (!selected.isNotVisible() && selected.get() != null && selected.get() == amount)
			return true;
		
		List<Rectangle> buttons = core.getImageAnalyzer().findContainers(
				bounds,
				SpriteID.BANK_TEXT_BUTTON_NW,
				SpriteID.BANK_TEXT_BUTTON_NE,
				SpriteID.BANK_TEXT_BUTTON_SW,
				SpriteID.BANK_TEXT_BUTTON_SE
		                                                                );
		
		if (buttons.isEmpty())
			return false;
		
		for (Rectangle btn : buttons) {
			String t = core.getOCR()
					.getText(Font.STANDARD_FONT, btn, ColorUtils.ORANGE_UI_TEXT)
					.replaceAll("[^0-9]", "");
			
			if (t.isEmpty()) continue;
			
			int v = Integer.parseInt(t);
			if (v == amount) {
				core.getFinger().tap(btn.getPadding(3));
				return core.submitTask(() -> {
					UIResult<Integer> now = getSelectedAmount();
					return !now.isNotVisible() && now.get() != null && now.get() == amount;
				}, 1500);
			}
		}
		
		return false;
	}

	@Override
	public Point getStartPoint() {
		Rectangle bounds = getBounds();
		if (bounds == null) return null;
		

		return new Point(bounds.x + 61, bounds.y + 40);
	}
	
	@Override public int groupWidth() { return 8; }
	@Override public int groupHeight() { return 4; }
	@Override public int xIncrement() { return 47; }
	@Override public int yIncrement() { return 47; }
	@Override public Rectangle getGroupBounds() { return getBounds(); }
	@Override public ScriptCore getCore() { return core; }
}
