package com.osmb.script.libation;

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

import java.awt.*;
import java.util.List;

public class WineShopInterface extends ComponentCentered implements ItemGroup {
	
	private static final Rectangle TITLE_BOUNDS =
			new Rectangle(117, 6, 476, 23);
	private static final Rectangle CLOSE_BUTTON =
			new Rectangle(460, 7, 21, 21);
	
	public WineShopInterface(ScriptCore core) {
		super(core);
	}
	
	@Override
	protected ComponentImage buildBackgroundImage() {
		Canvas canvas = new Canvas(488, 300, ColorUtils.TRANSPARENT_PIXEL);
		canvas.createBackground(core, BorderPalette.STEEL_BORDER, null);
		canvas.fillRect(
				5, 5,
				canvas.canvasWidth - 10,
				canvas.canvasHeight - 10,
				ColorUtils.TRANSPARENT_PIXEL
		               );
		return new ComponentImage<>(
				canvas.toSearchableImage(
						ToleranceComparator.ZERO_TOLERANCE,
						ColorModel.RGB
				                        ),
				-1,
				1
		);
	}
	
	@Override
	public boolean isVisible() {
		Rectangle bounds = getBounds();
		if (bounds == null)
			return false;
		
		Rectangle titleRect = bounds.getSubRectangle(TITLE_BOUNDS);
		
		String title = core.getOCR().getText(
				Font.STANDARD_FONT_BOLD,
				titleRect,
				ColorUtils.ORANGE_UI_TEXT
		                                    );
		
		return title.equalsIgnoreCase("Sunlight's Sanctum");
	}
	
	/* =====================================================
	 * CLOSE BUTTON
	 * ===================================================== */
	public void close() {
		Rectangle bounds = getBounds();
		if (bounds == null)
			return;
		
		Rectangle closeBtn = bounds.getSubRectangle(CLOSE_BUTTON);
		core.getFinger().tap(closeBtn);
		
		long end = System.currentTimeMillis() + 1500;
		while (System.currentTimeMillis() < end) {
			if (!isVisible()) return;
			core.sleep(30);
		}
	}
	
	/* =====================================================
	 * BUY AMOUNT BUTTONS
	 * ===================================================== */
	
	public UIResult<Integer> getSelectedAmount() {
		Rectangle bounds = getBounds();
		if (bounds == null)
			return UIResult.notVisible();
		
		List<Rectangle> redButtons =
				core.getImageAnalyzer().findContainers(
						bounds,
						SpriteID.BANK_TEXT_BUTTON_RED_NW,
						SpriteID.BANK_TEXT_BUTTON_RED_NE,
						SpriteID.BANK_TEXT_BUTTON_RED_SW,
						SpriteID.BANK_TEXT_BUTTON_RED_SE
				                                      );
		
		if (redButtons.isEmpty())
			return UIResult.notVisible();
		
		String text = core.getOCR().getText(
				Font.STANDARD_FONT,
				redButtons.get(0),
				ColorUtils.ORANGE_UI_TEXT
		                                   );
		
		text = text.replaceAll("[^0-9]", "");
		if (text.isEmpty())
			return UIResult.of(null);
		
		return UIResult.of(Integer.parseInt(text));
	}
	
	public boolean setSelectedAmount(int amount) {
		Rectangle bounds = getBounds();
		if (bounds == null)
			return false;
		
		UIResult<Integer> selected = getSelectedAmount();
		if (!selected.isNotVisible() &&
				selected.get() != null &&
				selected.get() == amount)
			return true;
		
		List<Rectangle> buttons =
				core.getImageAnalyzer().findContainers(
						bounds,
						SpriteID.BANK_TEXT_BUTTON_NW,
						SpriteID.BANK_TEXT_BUTTON_NE,
						SpriteID.BANK_TEXT_BUTTON_SW,
						SpriteID.BANK_TEXT_BUTTON_SE
				                                      );
		
		if (buttons.isEmpty())
			return false;
		
		for (Rectangle btn : buttons) {
			String text = core.getOCR().getText(
					Font.STANDARD_FONT,
					btn,
					ColorUtils.ORANGE_UI_TEXT
			                                   ).replaceAll("[^0-9]", "");
			
			if (text.isEmpty())
				continue;
			
			int btnAmount = Integer.parseInt(text);
			if (btnAmount == amount) {
				core.getFinger().tap(btn.getPadding(3));
				
				long end = System.currentTimeMillis() + 1500;
				while (System.currentTimeMillis() < end) {
					UIResult<Integer> now = getSelectedAmount();
					if (!now.isNotVisible() &&
							now.get() != null &&
							now.get() == amount)
						return true;
					
					core.sleep(40);
				}
				return false;
			}
		}
		
		return false;
	}
	

	@Override
	public ScriptCore getCore() {
		return core;
	}
	
	@Override
	public Point getStartPoint() {
		Rectangle bounds = getBounds();
		if (bounds == null)
			return null;
		
		return new Point(247, 356);
	}
	
	@Override
	public int groupWidth() {
		return 7;
	}
	
	@Override
	public int groupHeight() {
		return 3; // Shop has 3 rows
	}
	
	@Override
	public int xIncrement() {
		return 47; // Based on tile spacing
	}
	
	@Override
	public int yIncrement() {
		return 46; // Based on vertical spacing
	}
	
	@Override
	public Rectangle getGroupBounds() {
		return getBounds();
	}
}
