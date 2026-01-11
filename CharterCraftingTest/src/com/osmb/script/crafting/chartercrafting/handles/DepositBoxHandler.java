package com.osmb.script.crafting.chartercrafting.handles;

import java.util.Set;

import com.osmb.api.ScriptCore;
import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.item.ItemID;
import com.osmb.api.scene.RSObject;

import static com.osmb.script.crafting.chartercrafting.State.depositing;

public class DepositBoxHandler {
	private final ScriptCore core;
	
	public DepositBoxHandler(ScriptCore core) {
		this.core = core;
	}
	
	private static final int COINS = 995;
	private static final Set<Integer> ITEM_IDS_TO_NOT_DEPOSIT = Set.of(
			COINS,
			ItemID.MOLTEN_GLASS,
			ItemID.GLASSBLOWING_PIPE,
			ItemID.ASTRAL_RUNE);
	
	public void handle() {
		depositing = true;
		// 1) If deposit box UI is already open, handle it
		if (core.getWidgetManager().getDepositBox().isVisible()) {
			handleDepositInterface();
			return;
		}
		// 2) Find deposit box object in the scene
		RSObject box = core.getObjectManager()
				.getObject(o ->
						           o.getName() != null &&
								           o.getName().equalsIgnoreCase("bank deposit box") &&
								           o.canReach()
				          )
				.orElse(null);
		if (box == null) {
			core.log(getClass(), "No deposit box in scene.");
			return;
		}
		// 3) Open deposit box
		if (!box.interact("deposit")) {
			core.log(getClass(), "Failed to interact with deposit box.");
			return;
		}
		// 4) Wait until the deposit box widget is visible
		core.submitHumanTask(
				() -> core.getWidgetManager().getDepositBox().isVisible(),
				core.random(4000, 7000)
		                    );
	}
	
	private void handleDepositInterface() {
		ItemGroupResult snapshot =
				core.getWidgetManager()
						.getDepositBox()
						.search(ITEM_IDS_TO_NOT_DEPOSIT);
		if (snapshot == null) {
			return;
		}
		// Deposit everything EXCEPT coins (995)
		boolean success = core.getWidgetManager()
				.getDepositBox()
				.depositAll(ITEM_IDS_TO_NOT_DEPOSIT);
		if (!success) {
			core.log(getClass(), "Failed to deposit items.");
			return;
		}
		core.log(getClass(), "Deposited all items except coins.");
		// Close UI & release lock
		core.getWidgetManager().getDepositBox().close();
		depositing = false;
	}
}
