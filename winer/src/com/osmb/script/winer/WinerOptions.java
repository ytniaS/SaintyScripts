package com.osmb.script.winer;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

public class WinerOptions extends VBox {

    public WinerOptions(Consumer<WineMode> onStart) {

        getStyleClass().add("script-options");
        getStyleClass().add("dark-dialogue");

        setSpacing(10);
        setPadding(new Insets(10));

        Label title = new Label("Winer Options");

        ComboBox<WineMode> modeBox = new ComboBox<>();
        modeBox.getItems().addAll(
                WineMode.BUY_WINES,
                WineMode.MIX_WINES
        );
        modeBox.getSelectionModel().selectFirst();

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);

        grid.add(new Label("Mode"), 0, 0);
        grid.add(modeBox, 1, 0);

        Button start = new Button("Start Script");
        start.getStyleClass().add("action-bar-button");
        start.setOnAction(e -> {
            WineMode mode = modeBox.getSelectionModel().getSelectedItem();
            if (mode == null) {
                return;
            }
            onStart.accept(mode);
            start.getScene().getWindow().hide();
        });

        getChildren().addAll(title, grid, start);
    }
}
