package com.osmb.script.packbuyer;

import com.osmb.api.location.area.impl.RectangleArea;

public class PackModeConfig {
	public final BuyMode mode;
	public final String displayName;
	public final String shopTitle;
	public final RectangleArea area;
	public final int region;
	public final int packItemId;
	public final int openedItemId;
	public final int baseStock;
	public final int buyAmount;
	public int targetTotal;
	public int perWorld;
	public int totalOpened;
	
	public PackModeConfig(
			BuyMode mode,
			String displayName,
			String shopTitle,
			RectangleArea area,
			int region,
			int packItemId,
			int openedItemId,
			int baseStock,
			int buyAmount
	                     ) {
		this.mode = mode;
		this.displayName = displayName;
		this.shopTitle = shopTitle;
		this.area = area;
		this.region = region;
		this.packItemId = packItemId;
		this.openedItemId = openedItemId;
		this.baseStock = baseStock;
		this.buyAmount = buyAmount;
	}
	
	public PackModeConfig copy() {
		PackModeConfig c = new PackModeConfig(
				mode,
				displayName,
				shopTitle,
				area,
				region,
				packItemId,
				openedItemId,
				baseStock,
				buyAmount
		);
		c.targetTotal = targetTotal;
		c.perWorld = perWorld;
		return c;
	}
	
	@Override
	public String toString() {
		return displayName;
	}
}
