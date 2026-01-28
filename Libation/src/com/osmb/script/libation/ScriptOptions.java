package com.osmb.script.libation;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.prefs.Preferences;

public class ScriptOptions extends VBox {

    private final CheckBox sunfireBox;
    private final CheckBox bankedWineBox;

    public ScriptOptions() {

        setStyle("-fx-padding: 10; -fx-background-color: #636E72; -fx-spacing: 12");

        Label title = new Label("Libation Bowl Options");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: white;");

        sunfireBox = new CheckBox("Use Sunfire Splinters/Sunfire Wine");
        sunfireBox.setStyle("-fx-font-size: 14px; -fx-text-fill: white;");
        sunfireBox.setSelected(loadSunfirePref());

        bankedWineBox = new CheckBox("Use Banked Wines/Sunfire Wines");
        bankedWineBox.setStyle("-fx-font-size: 14px; -fx-text-fill: white;");
        bankedWineBox.setSelected(loadBankedWinePref());

        Button confirm = new Button("Confirm");
        confirm.setStyle("-fx-text-fill: white; -fx-font-size: 14px; -fx-font-weight: bold;");
        confirm.setOnAction(e -> {
            saveSunfirePref(sunfireBox.isSelected());
            saveBankedWinePref(bankedWineBox.isSelected());
            ((Stage) confirm.getScene().getWindow()).close();
        });

        HBox bottom = new HBox(confirm);
        bottom.setAlignment(Pos.CENTER_RIGHT);

        getChildren().addAll(
                title,
                sunfireBox,
                bankedWineBox,
                new Separator(),
                bottom
        );
    }

    private boolean loadSunfirePref() {
        return Preferences.userNodeForPackage(ScriptOptions.class)
                .getBoolean("use_sunfire", false);
    }

    private void saveSunfirePref(boolean value) {
        Preferences.userNodeForPackage(ScriptOptions.class)
                .putBoolean("use_sunfire", value);
    }

    private boolean loadBankedWinePref() {
        return Preferences.userNodeForPackage(ScriptOptions.class)
                .getBoolean("use_banked_wine", false);
    }

    private void saveBankedWinePref(boolean value) {
        Preferences.userNodeForPackage(ScriptOptions.class)
                .putBoolean("use_banked_wine", value);
    }

    public boolean useSunfire() {
        return sunfireBox.isSelected();
    }

    public boolean useBankedWine() {
        return bankedWineBox.isSelected();
    }
}
