package com.osmb.script.libation.javafx;

import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import java.util.prefs.Preferences;

public class ScriptOptions extends VBox {
	
	private final CheckBox sunfireBox;
	
	public ScriptOptions() {
		
		setStyle("-fx-padding: 10; -fx-background-color: #636E72; -fx-spacing: 12");
		
		Label title = new Label("Libation Bowl Options");
		title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: white;");
		
		sunfireBox = new CheckBox("Use Sunfire Splinters");
		sunfireBox.setStyle("-fx-font-size: 14px; -fx-text-fill: white;");
		sunfireBox.setSelected(loadSunfirePref());
		
		Button confirm = new Button("Confirm");
		confirm.setStyle("-fx-text-fill: white; -fx-font-size: 14px; -fx-font-weight: bold;");
		confirm.setOnAction(e -> {
			saveSunfirePref(sunfireBox.isSelected());
			((Stage) confirm.getScene().getWindow()).close();
		});
		
		HBox bottom = new HBox(confirm);
		bottom.setAlignment(Pos.CENTER_RIGHT);
		
		getChildren().addAll(title, sunfireBox, new Separator(), bottom);
	}
	
	private boolean loadSunfirePref() {
		return Preferences.userNodeForPackage(ScriptOptions.class)
				.getBoolean("use_sunfire", false);
	}
	
	private void saveSunfirePref(boolean value) {
		Preferences.userNodeForPackage(ScriptOptions.class)
				.putBoolean("use_sunfire", value);
	}
	
	public boolean useSunfire() {
		return sunfireBox.isSelected();
	}
}
