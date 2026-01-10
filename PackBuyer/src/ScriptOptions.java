package com.osmb.script.packbuyer.javafx;

import java.util.function.Consumer;

import com.osmb.script.packbuyer.PackModeConfig;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

public class ScriptOptions extends VBox {
	public ScriptOptions(
			PackModeConfig feathersGerrant,
			PackModeConfig broadSpira,
			PackModeConfig broadTurael,
			PackModeConfig amylaseGrace,
			Consumer<PackModeConfig> onStart
	                    ) {
		getStyleClass().add("script-options");
		getStyleClass().add("dark-dialogue");
		setSpacing(10);
		setPadding(new Insets(10));
		Label title = new Label("Pack Buyer Options");
		ComboBox<PackModeConfig> location = new ComboBox<>();
		location.getItems().addAll(
				feathersGerrant.copy(),
				broadSpira.copy(),
				broadTurael.copy(),
				amylaseGrace.copy()
		                          );
		location.getSelectionModel().selectFirst();
		TextField targetTotal = new TextField();
		TextField perWorld = new TextField();
		GridPane grid = new GridPane();
		grid.setHgap(10);
		grid.setVgap(8);
		grid.add(new Label("Location / Mode"), 0, 0);
		grid.add(location, 1, 0);
		grid.add(new Label("Target total (opened item)"), 0, 1);
		grid.add(targetTotal, 1, 1);
		grid.add(new Label("Per world (0 = unlimited)"), 0, 2);
		grid.add(perWorld, 1, 2);
		Button start = new Button("Start Script");
		start.getStyleClass().add("action-bar-button");
		start.setOnAction(e -> {
			PackModeConfig base = location.getSelectionModel().getSelectedItem();
			if (base == null) {return;}
			// Copy template ONCE
			PackModeConfig chosen = base.copy();
			// Target total (blank or invalid = 0)
			if (targetTotal.getText() != null && targetTotal.getText().matches("\\d+")) {
				chosen.targetTotal = Integer.parseInt(targetTotal.getText());
			} else {
				chosen.targetTotal = 0;
			}
			// Per world (blank or invalid = 0 → unlimited)
			if (perWorld.getText() != null && perWorld.getText().matches("\\d+")) {
				chosen.perWorld = Integer.parseInt(perWorld.getText());
			} else {
				chosen.perWorld = 0;
			}
			onStart.accept(chosen);
			start.getScene().getWindow().hide();
		});
		getChildren().addAll(title, grid, start);
	}
}
