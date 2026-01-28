package com.osmb.script.valetotemsfree.handler;

import com.osmb.api.input.MenuEntry;
import com.osmb.api.input.MenuHook;
import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.item.ItemSearchResult;
import com.osmb.api.location.area.Area;
import com.osmb.api.scene.RSObject;
import com.osmb.api.shape.Shape;
import com.osmb.api.ui.chatbox.dialogue.DialogueType;
import com.osmb.api.utils.RandomUtils;
import com.osmb.script.valetotemsfree.util.AreaDefinitions;

import java.util.Comparator;
import java.util.Set;
import java.util.function.BiFunction;

public class ValeTotemsFletchingHandler {
    private static final int FLETCHING_KNIFE_ID = 946;
    private static final int FLETCHING_KNIFE_ID_ALT = 31043; // Alternative fletching knife
    private static final Set<Integer> FLETCHING_KNIFE_IDS = Set.of(FLETCHING_KNIFE_ID, FLETCHING_KNIFE_ID_ALT);
    private static final Set<Integer> LOG_BASKET_IDS = Set.of(28140, 28142, 28143, 28145);

    // Resource requirements
    private static final int REQUIRED_LOGS_FLETCHING = 26;
    private static final int REQUIRED_BOWS_POST_TOTEM_5 = 12;
    private static final int REQUIRED_LOGS_POST_TOTEM_5 = 3;
    private static final int MIN_LOGS_FOR_FLETCHING = 5;
    private static final int INVENTORY_SIZE = 27;

    private final ValeTotemsContext context;
    private BiFunction<Integer, Integer, Boolean> withdrawItemCallback; // (itemId, amount) -> success

    public ValeTotemsFletchingHandler(ValeTotemsContext context) {
        this.context = context;
    }

    public void setWithdrawItemCallback(BiFunction<Integer, Integer, Boolean> callback) {
        this.withdrawItemCallback = callback;
    }

    public boolean performFletching(int requiredBows) {
        if (context.getScript().getWidgetManager().getBank().isVisible()) {
            context.getScript().getWidgetManager().getBank().close();
            context.getScript().pollFramesHuman(() -> true, RandomUtils.gaussianRandom(80, 500, 120, 120), false);
        }

        context.getScript().getWidgetManager().getInventory().open();
        ItemGroupResult knifeSearch = context.getScript().getWidgetManager().getInventory().search(FLETCHING_KNIFE_IDS);
        ItemGroupResult logSearch = context.getScript().getWidgetManager().getInventory().search(Set.of(context.getSelectedLogId()));
        if (knifeSearch == null || logSearch == null) {
            return false;
        }

        ItemSearchResult knifeItem = null;
        for (int knifeId : FLETCHING_KNIFE_IDS) {
            knifeItem = knifeSearch.getItem(new int[]{knifeId});
            if (knifeItem != null) break;
        }
        ItemSearchResult logItem = logSearch.getItem(new int[]{context.getSelectedLogId()});
        if (knifeItem == null || logItem == null) {
            return false;
        }

        context.getScript().getFinger().tap((Shape) knifeItem.getBounds());
        context.getScript().pollFramesHuman(() -> true, RandomUtils.gaussianRandom(100, 600, 150, 150), false);
        context.getScript().getFinger().tap((Shape) logItem.getBounds());

        boolean dialogueAppeared = context.getScript().pollFramesUntil(() -> context.getScript().getWidgetManager().getDialogue().isVisible(), getDialogueTimeout(), false, false);
        if (!dialogueAppeared) {
            log("Fletching dialogue did not appear");
            return false;
        }

        if (context.getScript().getWidgetManager().getDialogue().selectItem(new int[]{context.getSelectedProductId()})) {
            int startingBows = getItemCount(context.getSelectedProductId());
            int bowsToFletch = requiredBows - startingBows;
            // Randomized fletching timeout
            int timeout = getFletchTimeout();
            log("Fletching " + bowsToFletch + " bows, timeout: " + timeout + "ms");
            boolean fletchingComplete = context.getScript().pollFramesUntil(() -> getItemCount(context.getSelectedProductId()) >= requiredBows, timeout, false, false);
            int finalBows = getItemCount(context.getSelectedProductId());
            if (!fletchingComplete && finalBows == startingBows) {
                log("Fletching may have failed - no progress detected");
            } else if (!fletchingComplete) {
                log("Fletching incomplete - timeout reached. Have " + finalBows + " bows, need " + requiredBows);
            }
            return fletchingComplete || finalBows >= requiredBows;
        }
        return false;
    }

    public boolean fillBasketWithLogs() {
        ItemSearchResult basketItem = findBasketInInventory();
        if (basketItem == null) {
            return false;
        }

        int availableLogs = getItemCount(context.getSelectedLogId());
        if (availableLogs < REQUIRED_LOGS_FLETCHING) {
            if (withdrawItemCallback != null) {
                if (!withdrawItemCallback.apply(context.getSelectedLogId(), INVENTORY_SIZE)) {
                    return false;
                }
            } else {
                log("WARNING: No withdraw callback set for basket fill");
                return false;
            }
            context.getScript().pollFramesUntil(() -> getItemCount(context.getSelectedLogId()) >= REQUIRED_LOGS_FLETCHING, getBankWithdrawTimeout(), false, false);
        }

        if (context.getScript().getWidgetManager().getBank().isVisible()) {
            context.getScript().getWidgetManager().getBank().close();
            context.getScript().pollFramesHuman(() -> true, RandomUtils.gaussianRandom(150, 800, 200, 200), false);
        }

        int initialLogCount = getItemCount(context.getSelectedLogId());
        if (basketItem.interact("Fill")) {
            boolean logsTransferred = context.getScript().pollFramesUntil(() -> getItemCount(context.getSelectedLogId()) < initialLogCount, 3000, false, false);
            int afterFillCount = getItemCount(context.getSelectedLogId());
            if (!logsTransferred && afterFillCount == initialLogCount) {
                // don't retry indefinitely and time out every 3s (safeguard for full basket at start).
                log("Basket already full or no logs transferred - continuing");
                reopenBankAfterBasketFill();
                return true;
            }
            context.getScript().pollFramesHuman(() -> true, RandomUtils.gaussianRandom(200, 1200, 300, 300), false);
            reopenBankAfterBasketFill();
            return logsTransferred || getItemCount(context.getSelectedLogId()) < initialLogCount;
        }
        return false;
    }

    private void reopenBankAfterBasketFill() {
        RSObject bankObj = findBankObject(AreaDefinitions.BANK_AREA);
        if (bankObj != null) {
            MenuHook bankMenuHook = list -> {
                if (list == null || list.isEmpty()) return null;
                for (MenuEntry entry : list) {
                    String action = entry.getAction();
                    if (action != null) {
                        String lower = action.toLowerCase();
                        if (lower.contains("bank") || lower.contains("use")) {
                            return entry;
                        }
                    }
                }
                return null;
            };
            if (bankObj.interact(bankMenuHook)) {
                context.getScript().pollFramesUntil(() -> context.getScript().getWidgetManager().getBank().isVisible(), getDialogueTimeout(), false, false);
            }
        }
    }

    public boolean processBasketAfterTotem5() {
        if (!context.isUseLogBasket()) {
            return true;
        }

        if (context.isBasketEmptiedAfterTotem5() && context.isPostTotem5FletchingDone()) {
            return true;
        }

        ItemSearchResult basketItem = findBasketInInventory();
        if (basketItem == null) {
            return false;
        }

        if (context.getScript().getWidgetManager().getDialogue().isVisible()) {
            DialogueType dialogueType = context.getScript().getWidgetManager().getDialogue().getDialogueType();
            if (dialogueType == DialogueType.TAP_HERE_TO_CONTINUE) {
                if (!context.isBasketEmptiedAfterTotem5() && context.getLogsBeforeBasketEmpty() >= 0) {
                    int currentLogs = getItemCount(context.getSelectedLogId());
                    if (currentLogs == context.getLogsBeforeBasketEmpty()) {
                        // Basket was empty or no logs transferred; treat as emptied to prevent looping
                        context.setBasketEmptiedAfterTotem5(true);
                    } else if (currentLogs > context.getLogsBeforeBasketEmpty()) {
                        context.setBasketEmptiedAfterTotem5(true);
                    }
                    context.setLogsBeforeBasketEmpty(-1);
                }
                context.getScript().getWidgetManager().getDialogue().continueChatDialogue();
                context.getScript().pollFramesHuman(() -> true, RandomUtils.gaussianRandom(150, 800, 200, 200), false);
                return false;
            }
            if (dialogueType == DialogueType.TEXT_OPTION) {
                int logsBeforeEmpty = getItemCount(context.getSelectedLogId());
                context.getScript().getWidgetManager().getDialogue().selectOption("Yes");
                boolean logsReceived = context.getScript().pollFramesUntil(() -> getItemCount(context.getSelectedLogId()) > logsBeforeEmpty, 3000, false, false);
                if (logsReceived || getItemCount(context.getSelectedLogId()) > logsBeforeEmpty) {
                    context.setBasketEmptiedAfterTotem5(true);
                }
                context.setLogsBeforeBasketEmpty(-1);
                return false;
            }
        }

        if (!context.isBasketEmptiedAfterTotem5()) {
            if (basketItem.interact("Empty") || basketItem.interact("Check")) {
                context.setLogsBeforeBasketEmpty(getItemCount(context.getSelectedLogId()));
                context.getScript().pollFramesUntil(() -> context.getScript().getWidgetManager().getDialogue().isVisible(), 3000, false, false);
                return false;
            }
        }

        if (context.isBasketEmptiedAfterTotem5() && !context.isPostTotem5FletchingDone()) {
            int currentBows = getItemCount(context.getSelectedProductId());
            int currentLogs = getItemCount(context.getSelectedLogId());

            if (currentBows < REQUIRED_BOWS_POST_TOTEM_5 && currentLogs >= MIN_LOGS_FOR_FLETCHING) {
                if (performFletching(REQUIRED_BOWS_POST_TOTEM_5)) {
                    int finalBows = getItemCount(context.getSelectedProductId());
                    if (finalBows >= REQUIRED_BOWS_POST_TOTEM_5) {
                        context.setPostTotem5FletchingDone(true);
                    } else {
                        log("Post-Totem-5 fletching incomplete - have " + finalBows + ", need " + REQUIRED_BOWS_POST_TOTEM_5);
                    }
                }
                return false;
            } else if (currentBows >= REQUIRED_BOWS_POST_TOTEM_5 && currentLogs >= REQUIRED_LOGS_POST_TOTEM_5) {
                context.setPostTotem5FletchingDone(true);
                return true;
            }
        }

        return false;
    }

    // Helper methods
    private ItemSearchResult findBasketInInventory() {
        ItemGroupResult basketResult = context.getScript().getWidgetManager().getInventory().search(LOG_BASKET_IDS);
        if (basketResult == null) {
            log("Log basket not found in inventory!");
            return null;
        }

        for (int basketId : LOG_BASKET_IDS) {
            ItemSearchResult item = basketResult.getItem(new int[]{basketId});
            if (item != null) {
                return item;
            }
        }

        log("Could not find basket item!");
        return null;
    }

    private RSObject findBankObject(Area area) {
        String objectName = area == AreaDefinitions.BUFFALO_AREA ? "buffalo" : "Bank booth";
        return context.getScript().getObjectManager().getObjects(rSObject ->
                        rSObject != null &&
                                rSObject.getName() != null &&
                                rSObject.getName().toLowerCase().contains(objectName.toLowerCase()) &&
                                area.contains(rSObject.getWorldPosition())).stream()
                .min(Comparator.comparingDouble(rSObject ->
                        rSObject.getWorldPosition().distanceTo((com.osmb.api.location.position.Position) context.getScript().getWorldPosition())))
                .orElse(null);
    }

    private int getItemCount(int itemId) {
        ItemGroupResult itemGroupResult = context.getScript().getWidgetManager().getInventory().search(Set.of(itemId));
        return itemGroupResult != null ? itemGroupResult.getAmount(new int[]{itemId}) : 0;
    }

    private int getFletchTimeout() {
        return RandomUtils.uniformRandom(28000, 32000);
    }

    private int getBankWithdrawTimeout() {
        return RandomUtils.uniformRandom(2500, 3500);
    }

    private int getDialogueTimeout() {
        return RandomUtils.uniformRandom(3500, 4500);
    }

    private void log(String message) {
        context.getScript().log("ValeTotems", message);
    }
}
