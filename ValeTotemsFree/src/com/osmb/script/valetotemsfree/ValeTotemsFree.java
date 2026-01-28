package com.osmb.script.valetotemsfree;

import com.osmb.api.script.Script;
import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.script.SkillCategory;
import com.osmb.api.ui.component.tabs.SettingsTabComponent;
import com.osmb.api.utils.RandomUtils;
import com.osmb.api.visual.drawing.Canvas;
import com.osmb.script.valetotemsfree.ui.SettingsUI;
import com.sainty.common.Telemetry;
import com.sainty.common.VersionChecker;
import javafx.scene.Parent;
import javafx.scene.Scene;

import java.util.Map;


@ScriptDefinition(
        name = "Vale Totems Free",
        author = "Sainty",
        version = 3.0,
        description = "Free Script for vale totems minigame.",
        skillCategory = SkillCategory.FLETCHING
)


public class ValeTotemsFree
        extends Script {
    private ValeTotemsController controller;
    private static final String SCRIPT_NAME = "ValeTotemsFree";
    private long scriptStartTime;

    public ValeTotemsFree(Object object) {
        super(object);
    }

    @Override
    public void onStart() {
        if (!VersionChecker.isExactVersion(this)) {
            stop();
            return;
        }
        ensureMaxZoom();
        scriptStartTime = System.currentTimeMillis();
        Telemetry.sessionStart(SCRIPT_NAME);

        controller = new ValeTotemsController(this);
        SettingsUI ui = new SettingsUI(settings -> {
            controller.initialize(settings);
        });
        Scene scene = new Scene((Parent) ui);
        scene.getStylesheets().add("style.css");
        this.getStageController().show(scene, "Vale Totems Free Settings", false);
    }


    public int poll() {

        Telemetry.flush(
                SCRIPT_NAME,
                scriptStartTime,
                Map.of(
                        "fletching_xp_gained", (long) controller.getXP(),
                        "totems_built", (long) controller.getTotemsCompleted(),
                        "runtime_seconds", (System.currentTimeMillis() - scriptStartTime) / 1000
                )
        );


        if (controller != null) {
            return controller.execute();
        }
        return 1000;
    }

    private void ensureMaxZoom() {
        if (!getWidgetManager().getSettings()
                .openSubTab(SettingsTabComponent.SettingsSubTabType.DISPLAY_TAB)) {
            log("ValeTotems", "Failed to open display settings tab");
            return;
        }
        var zoomResult = getWidgetManager()
                .getSettings()
                .getZoomLevel();
        Integer zoom = zoomResult != null ? zoomResult.get() : null;
        if (zoom == null) {
            log("ValeTotems", "Failed to read zoom level");
            return;
        }
        log("ValeTotems", "Current zoom level: " + zoom);
        // Set zoom to 0 (maximum zoom-out)
        if (zoom > 1) {
            if (getWidgetManager().getSettings().setZoomLevel(0)) {
                log("ValeTotems", "Zoom set to 0");
                pollFramesHuman(
                        () -> true,
                        RandomUtils.gaussianRandom(300, 1500, 350, 350)
                );
            } else {
                log("ValeTotems", "Failed to set zoom level");
            }
        }
    }

    public void onPaint(Canvas canvas) {
        if (controller != null) {
            controller.onPaint(canvas);
        }
    }

    public void onNewFrame() {
        if (controller != null) {
            controller.onNewFrame();
        }
    }

    public boolean stopped() {
        if (super.stopped()) {
            if (controller != null) {
                controller.onStop();
            }
            return true;
        }
        return false;
    }

    public boolean canHopWorlds() {
        return this.controller != null && this.controller.canHopWorlds();
    }

    public boolean canAFK() {
        return this.controller != null && this.controller.canAFK();
    }

    public boolean promptBankTabDialogue() {
        return true;
    }

    public int[] regionsToPrioritise() {
        if (this.controller != null) {
            return this.controller.getRegionsToPrioritise();
        } else {
            return new int[]{5427, 5428, 5684, 5683};
        }
    }
}

