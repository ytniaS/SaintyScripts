package com.osmb.script.valetotemsfree.ui;

import com.osmb.script.valetotemsfree.config.ConfigStore;
import com.osmb.script.valetotemsfree.model.LogType;
import com.osmb.script.valetotemsfree.model.PremadeData;
import com.osmb.script.valetotemsfree.model.PremadeItem;
import com.osmb.script.valetotemsfree.model.ProductType;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.util.Properties;
import java.util.function.Consumer;

public class SettingsUI extends VBox {

    public enum Mode {
        FLETCHING,
        PRE_MADE
    }

    public static class Settings {
        public final Mode mode;
        public final boolean useLogBasket;
        public final LogType logType;
        public final ProductType productType;
        public final PremadeItem premadeItem;
        public final boolean knifeEquipped;

        public Settings(
                Mode mode,
                boolean useLogBasket,
                LogType logType,
                ProductType productType,
                PremadeItem premadeItem,
                boolean knifeEquipped
        ) {
            this.mode = mode;
            this.useLogBasket = useLogBasket;
            this.logType = logType;
            this.productType = productType;
            this.premadeItem = premadeItem;
            this.knifeEquipped = knifeEquipped;
        }
    }

    public SettingsUI(Consumer<Settings> onStart) {

        getStyleClass().add("script-options");
        getStyleClass().add("dark-dialogue");

        setSpacing(10);
        setPadding(new Insets(10));

        Label title = new Label("Vale Totems Free");

        Properties props = ConfigStore.load();

        Mode savedMode = Mode.valueOf(
                props.getProperty("mode", Mode.FLETCHING.name())
        );

        boolean savedBasket =
                Boolean.parseBoolean(props.getProperty("logBasket", "false"));

        LogType savedLog =
                LogType.valueOf(props.getProperty("logType", LogType.OAK.name()));

        ProductType savedProduct =
                ProductType.valueOf(props.getProperty("productType", ProductType.LONGBOW_U.name()));

        boolean savedKnife =
                Boolean.parseBoolean(props.getProperty("knifeEquipped", "false"));

        ComboBox<Mode> modeBox = new ComboBox<>();
        modeBox.getItems().addAll(Mode.FLETCHING, Mode.PRE_MADE);
        modeBox.getSelectionModel().select(savedMode);

        CheckBox logBasketBox = new CheckBox("Use Log Basket");
        logBasketBox.setSelected(savedBasket);
        // Re-enabled - log basket support reintroduced

        ComboBox<LogType> logBox = new ComboBox<>();
        logBox.getItems().addAll(LogType.values());
        logBox.getSelectionModel().select(savedLog);

        ComboBox<ProductType> productBox = new ComboBox<>();
        productBox.getItems().addAll(ProductType.values());
        productBox.getSelectionModel().select(savedProduct);

        CheckBox knifeEquippedBox = new CheckBox("Knife Equipped");
        knifeEquippedBox.setSelected(savedKnife);
        // Initially disable if not in premade mode
        knifeEquippedBox.setDisable(savedMode != Mode.PRE_MADE);

        ComboBox<PremadeItem> premadeItemBox = new ComboBox<>();

        Runnable refreshPremade = () -> {
            premadeItemBox.getItems().clear();
            if (logBox.getValue() != null) {
                var list = PremadeData.DATA.get(logBox.getValue());
                if (list != null) {
                    premadeItemBox.getItems().addAll(list);
                    premadeItemBox.getSelectionModel().selectFirst();
                }
            }
        };

        logBox.valueProperty().addListener((a, b, c) -> {
            refreshPremade.run();
            boolean isPremade = modeBox.getValue() == Mode.PRE_MADE;
            productBox.setDisable(c == LogType.REDWOOD || isPremade);
            // Log basket is always disabled - API doesn't support hover/submenu
        });

        modeBox.valueProperty().addListener((a, b, mode) -> {
            boolean isPremade = mode == Mode.PRE_MADE;
            // Enable knife equipped checkbox only in PRE_MADE mode (can be checked or unchecked)
            knifeEquippedBox.setDisable(!isPremade);
            if (!isPremade) {
                knifeEquippedBox.setSelected(false);
            }
            // Disable/enable premade item box based on mode
            premadeItemBox.setDisable(!isPremade);
            // Update product box disable state based on mode
            LogType currentLog = logBox.getValue();
            if (currentLog != null) {
                productBox.setDisable(currentLog == LogType.REDWOOD || isPremade);
            }
            // Refresh premade items when switching to PRE_MADE mode
            if (isPremade) {
                refreshPremade.run();
            }
        });

        // Initial setup based on saved mode
        boolean initialIsPremade = savedMode == Mode.PRE_MADE;
        productBox.setDisable(savedLog == LogType.REDWOOD || initialIsPremade);
        premadeItemBox.setDisable(!initialIsPremade);
        knifeEquippedBox.setDisable(!initialIsPremade);
        if (!initialIsPremade) {
            knifeEquippedBox.setSelected(false);
        }

        refreshPremade.run();

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);

        int r = 0;
        grid.add(new Label("Mode"), 0, r);
        grid.add(modeBox, 1, r++);

        grid.add(new Label("Log Type"), 0, r);
        grid.add(logBox, 1, r++);

        grid.add(new Label("Product"), 0, r);
        grid.add(productBox, 1, r++);

        grid.add(new Label("Pre-Made Item"), 0, r);
        grid.add(premadeItemBox, 1, r++);

        grid.add(new Label("Knife Equipped"), 0, r);
        grid.add(knifeEquippedBox, 1, r++);

        grid.add(new Label("Log Basket"), 0, r);
        grid.add(logBasketBox, 1, r++);

        Button start = new Button("Start Script");
        start.getStyleClass().add("action-bar-button");

        start.setOnAction(e -> {

            Properties save = new Properties();
            save.setProperty("mode", modeBox.getValue().name());
            save.setProperty("logBasket", String.valueOf(logBasketBox.isSelected()));
            save.setProperty("logType", logBox.getValue().name());
            save.setProperty("productType", productBox.getValue().name());
            save.setProperty("knifeEquipped", String.valueOf(knifeEquippedBox.isSelected()));
            ConfigStore.save(save);

            onStart.accept(new Settings(
                    modeBox.getValue(),
                    logBasketBox.isSelected(),
                    logBox.getValue(),
                    productBox.getValue(),
                    premadeItemBox.getValue(),
                    knifeEquippedBox.isSelected()
            ));

            start.getScene().getWindow().hide();
        });

        getChildren().addAll(
                title,
                grid,
                start
        );
    }
}
