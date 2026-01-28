package com.osmb.script.valetotemsfree.handler;

import com.osmb.api.script.Script;
import com.osmb.api.trackers.experience.XPTracker;


public class ValeTotemsContext {
    private final Script script;

    // Configuration
    private int selectedLogId;
    private int selectedProductId;
    private int selectedPreMadeItemId;
    private boolean usePreMadeItems;
    private boolean useLogBasket;
    private boolean fletchingKnifeEquipped;

    // State
    private boolean basketRefilledThisLoop = false;
    private boolean basketEmptiedAfterTotem5 = false;
    private boolean postTotem5FletchingDone = false;
    private int logsBeforeBasketEmpty = -1;
    private XPTracker fletchingXP;

    public ValeTotemsContext(Script script) {
        this.script = script;
    }

    // Getters and setters
    public Script getScript() {
        return script;
    }

    public int getSelectedLogId() {
        return selectedLogId;
    }

    public void setSelectedLogId(int selectedLogId) {
        this.selectedLogId = selectedLogId;
    }

    public int getSelectedProductId() {
        return selectedProductId;
    }

    public void setSelectedProductId(int selectedProductId) {
        this.selectedProductId = selectedProductId;
    }

    public int getSelectedPreMadeItemId() {
        return selectedPreMadeItemId;
    }

    public void setSelectedPreMadeItemId(int selectedPreMadeItemId) {
        this.selectedPreMadeItemId = selectedPreMadeItemId;
    }

    public boolean isUsePreMadeItems() {
        return usePreMadeItems;
    }

    public void setUsePreMadeItems(boolean usePreMadeItems) {
        this.usePreMadeItems = usePreMadeItems;
    }

    public boolean isUseLogBasket() {
        return useLogBasket;
    }

    public void setUseLogBasket(boolean useLogBasket) {
        this.useLogBasket = useLogBasket;
    }

    public boolean isFletchingKnifeEquipped() {
        return fletchingKnifeEquipped;
    }

    public void setFletchingKnifeEquipped(boolean fletchingKnifeEquipped) {
        this.fletchingKnifeEquipped = fletchingKnifeEquipped;
    }

    public boolean isBasketRefilledThisLoop() {
        return basketRefilledThisLoop;
    }

    public void setBasketRefilledThisLoop(boolean basketRefilledThisLoop) {
        this.basketRefilledThisLoop = basketRefilledThisLoop;
    }

    public boolean isBasketEmptiedAfterTotem5() {
        return basketEmptiedAfterTotem5;
    }

    public void setBasketEmptiedAfterTotem5(boolean basketEmptiedAfterTotem5) {
        this.basketEmptiedAfterTotem5 = basketEmptiedAfterTotem5;
    }

    public boolean isPostTotem5FletchingDone() {
        return postTotem5FletchingDone;
    }

    public void setPostTotem5FletchingDone(boolean postTotem5FletchingDone) {
        this.postTotem5FletchingDone = postTotem5FletchingDone;
    }

    public int getLogsBeforeBasketEmpty() {
        return logsBeforeBasketEmpty;
    }

    public void setLogsBeforeBasketEmpty(int logsBeforeBasketEmpty) {
        this.logsBeforeBasketEmpty = logsBeforeBasketEmpty;
    }

    public XPTracker getFletchingXP() {
        return fletchingXP;
    }

    public void setFletchingXP(XPTracker fletchingXP) {
        this.fletchingXP = fletchingXP;
    }
}
