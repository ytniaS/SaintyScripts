package com.osmb.script.babayaga;

public class RuneConfig {
	
	public final int itemId;
	public final String name;
	public final int baseStock;
	
	public boolean enabled = false;
	public int targetTotal = 0;
	public int perWorld = 0;
	
	public int totalBought = 0;
	
	public RuneConfig(int itemId, String name, int baseStock) {
		this.itemId = itemId;
		this.name = name;
		this.baseStock = baseStock;
	}
	
	public void resetWorld() {
	}
}
