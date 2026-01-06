package com.osmb.script.babayaga;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.util.List;

public class ScriptOptions extends VBox {
	
	public ScriptOptions(List<RuneConfig> runes) {
		getStyleClass().add("script-options");
		getStyleClass().add("dark-dialogue");
		setSpacing(10);
		setPadding(new Insets(10));
		
		GridPane grid = new GridPane();
		grid.getStyleClass().add("rune-grid");
		grid.setHgap(10);
		grid.setVgap(6);
		
		grid.add(new Label("Enable"), 0, 0);
		grid.add(new Label("Rune"), 1, 0);
		grid.add(new Label("Target Total"), 2, 0);
		grid.add(new Label("Per World"), 3, 0);
		
		int row = 1;
		for (RuneConfig r : runes) {
			
			CheckBox enable = new CheckBox();
			TextField total = new TextField("0");
			TextField perWorld = new TextField("0");
			
			enable.setSelected(r.enabled);
			
			enable.selectedProperty().addListener((a, b, c) -> r.enabled = c);
			
			total.textProperty().addListener((a, b, c) -> {
				if (c != null && c.matches("\\d+"))
					r.targetTotal = Integer.parseInt(c);
			});
			
			perWorld.textProperty().addListener((a, b, c) -> {
				if (c != null && c.matches("\\d+"))
					r.perWorld = Integer.parseInt(c);
			});
			
			grid.add(enable, 0, row);
			grid.add(new Label(r.name), 1, row);
			grid.add(total, 2, row);
			grid.add(perWorld, 3, row);
			row++;
			
		}
		
		Button start = new Button("Start Script");
		start.getStyleClass().add("action-bar-button");
		start.setOnAction(e ->
				                  start.getScene().getWindow().hide()
		                 );
		
		getChildren().addAll(grid, start);
	}
}
