package com.osmb.script.oneclick50fmv2;

import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.script.Script;
import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.script.SkillCategory;
import com.osmb.api.trackers.experience.XPTracker;
import com.osmb.api.ui.chatbox.ChatboxFilterTab;
import com.osmb.api.ui.component.tabs.skill.SkillType;
import com.osmb.api.utils.UIResultList;
import com.osmb.api.visual.drawing.Canvas;
import com.osmb.script.oneclick50fmv2.data.Tree;
import com.osmb.script.oneclick50fmv2.tasks.BurnLogs;
import com.osmb.script.oneclick50fmv2.tasks.ChopTrees;
import com.osmb.script.oneclick50fmv2.tasks.LightBonfire;
import com.osmb.script.oneclick50fmv2.tasks.Setup;
import com.osmb.script.oneclick50fmv2.utils.Task;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


@ScriptDefinition(
        name = "OneClick 50 FM V2",
        description = "Oneclick 1-50 Firemaking at Castle Wars",
        version = 1.0,
        author = "Sainty",
        skillCategory = SkillCategory.FIREMAKING
)
public class OneClick50FM extends Script {

    public static boolean setupComplete = false;
    public static String currentTask = "Starting...";
    public static Tree selectedTree = null;
    public static WorldPosition bonfirePosition = null;
    public static long bonfireSetAtMs = 0L;
    public static boolean forceNewLightPosition = false;
    public static volatile boolean fireLitFromChat = false;
    public static int cachedFmLevel = 1;
    public static int cachedWcLevel = 1;
    private static long lastSkillCheckMs = 0;
    private static final long SKILL_CACHE_INTERVAL_MS = 5 * 60 * 1000;

    private static final List<String> previousChatboxLines = new ArrayList<>();

    public static int initialWcLevel = 0;
    public static int initialFmLevel = 0;
    public static int logsBurnt = 0;

    private XPTracker fmXP;
    private XPTracker wcXP;

    private final long startTime = System.currentTimeMillis();

    private static final long XP_TO_50 = 101333;
    private static final int EST_XP_PER_LOG = 50;

    private List<Task> tasks;

    public OneClick50FM(Object scriptCore) {
        super(scriptCore);
    }

    @Override
    public void onStart() {
        log(getClass(), "Starting 1-50 Firemaking at Castle Wars");
        forceNewLightPosition = false;

        var trackers = getXPTrackers();
        fmXP = trackers != null ? trackers.get(SkillType.FIREMAKING) : null;
        wcXP = trackers != null ? trackers.get(SkillType.WOODCUTTING) : null;

        tasks = Arrays.<Task>asList(
                new Setup(this),
                new BurnLogs(this),
                new LightBonfire(this),
                new ChopTrees(this)
        );
    }

    @Override
    public void onNewFrame() {
        if (fmXP == null || wcXP == null) {
            var trackers = getXPTrackers();
            if (trackers != null) {
                if (fmXP == null) fmXP = trackers.get(SkillType.FIREMAKING);
                if (wcXP == null) wcXP = trackers.get(SkillType.WOODCUTTING);
            }
        }
    }

    @Override
    public int poll() {
        if (setupComplete) {
            pumpChatbox();
        }

        if (setupComplete && fmXP != null) {
            int currentLevel = fmXP.getLevel();

            if (currentLevel >= 50) {
                log(getClass(), "=== LEVEL 50 REACHED ===");
                log(getClass(), "Congratulations! Stopping script.");
                stop();
                return 0;
            }
        }

        if (setupComplete) {
            long now = System.currentTimeMillis();
            if (now - lastSkillCheckMs > SKILL_CACHE_INTERVAL_MS) {
                var wm = getWidgetManager();
                if (wm != null) {
                    var tab = wm.getSkillTab();
                    if (tab != null) {
                        var fm = tab.getSkillLevel(SkillType.FIREMAKING);
                        var wc = tab.getSkillLevel(SkillType.WOODCUTTING);
                        if (fm != null) cachedFmLevel = Math.max(1, fm.getLevel());
                        if (wc != null) cachedWcLevel = Math.max(1, wc.getLevel());
                    }
                }
                lastSkillCheckMs = now;
            }
        }

        if (tasks != null) {
            for (Task task : tasks) {
                if (task != null && task.activate()) {
                    task.execute();
                    return 0;
                }
            }
        }

        currentTask = "Idle";
        return 600;
    }

    @Override
    public int[] regionsToPrioritise() {
        return new int[]{9776}; // Castle Wars
    }

    public void pumpChatbox() {
        listenChatbox();
    }

    private void listenChatbox() {
        var wm = getWidgetManager();
        if (wm == null) return;
        var chatbox = wm.getChatbox();
        if (chatbox == null) return;
        if (wm.getDialogue() != null && wm.getDialogue().getDialogueType() != null) return;
        if (chatbox.getActiveFilterTab() != ChatboxFilterTab.GAME) {
            chatbox.openFilterTab(ChatboxFilterTab.GAME);
            return;
        }
        UIResultList textLines = chatbox.getText();
        if (textLines == null || textLines.isNotVisible()) return;
        List<String> currentLines = textLines.asList();
        if (currentLines == null || currentLines.isEmpty()) return;

        int firstDifference = 0;
        if (!previousChatboxLines.isEmpty()) {
            if (currentLines.equals(previousChatboxLines)) return;
            int currSize = currentLines.size();
            int prevSize = previousChatboxLines.size();
            for (int i = 0; i < currSize; i++) {
                int suffixLen = currSize - i;
                if (suffixLen > prevSize) continue;
                boolean match = true;
                for (int j = 0; j < suffixLen; j++) {
                    if (!currentLines.get(i + j).equals(previousChatboxLines.get(j))) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    firstDifference = i;
                    break;
                }
            }
        }
        previousChatboxLines.clear();
        previousChatboxLines.addAll(currentLines);
        if (firstDifference > 0) {
            onNewChatboxMessage(currentLines.subList(0, firstDifference));
        }
    }

    private void onNewChatboxMessage(List<String> newLines) {
        if (newLines == null) return;
        for (String line : newLines) {
            if (line == null) continue;
            String lower = line.toLowerCase().trim();
            if (lower.contains("you light a fire") || lower.contains("you light the fire")
                    || lower.contains("the fire catches and the logs begin to burn")
                    || lower.contains("the logs catch fire and begin to burn")) {
                fireLitFromChat = true;
            } else if (lower.endsWith("further away.")) {
                log(getClass(), "Chat: further away – clearing bonfire");
                bonfirePosition = null;
            } else if (lower.endsWith("light a fire here.") || lower.contains("can't light a fire here")) {
                log(getClass(), "Chat: can't light here – force new position");
                forceNewLightPosition = true;
                bonfirePosition = null;
            } else if (lower.endsWith("fire has burned out.")) {
                log(getClass(), "Chat: fire burned out – clearing bonfire");
                bonfirePosition = null;
            }
        }
    }

    @Override
    public void onPaint(Canvas c) {
        long elapsed = Math.max(1, System.currentTimeMillis() - startTime);

        if (fmXP == null || wcXP == null) {
            var trackers = getXPTrackers();
            if (trackers != null) {
                if (fmXP == null) fmXP = trackers.get(SkillType.FIREMAKING);
                if (wcXP == null) wcXP = trackers.get(SkillType.WOODCUTTING);
            }
        }

        int x = 16;
        int y = 40;
        int w = 300;
        int headerH = 45;
        int bodyH = 280;
        int lineH = 16;

        int BG = new Color(12, 14, 20, 235).getRGB();
        int BORDER = new Color(100, 100, 110, 180).getRGB();
        int DIVIDER = new Color(255, 255, 255, 40).getRGB();
        c.fillRect(x, y, w, headerH + bodyH, BG, 1);
        c.drawRect(x, y, w, headerH + bodyH, BORDER);
        drawHeader(c, "Sainty", "One Click 50 FM", x + 14, y + 16);
        c.fillRect(x + 10, y + headerH, w - 20, 1, DIVIDER);

        Font body = new Font("Segoe UI", Font.PLAIN, 13);
        int ty = y + headerH + 18;

        c.drawText("State: " + currentTask, x + 14, ty, 0xFFAAAAFF, body);
        ty += lineH;
        String treeStr = selectedTree != null ? selectedTree.getObjectName() : "None";
        c.drawText("Tree: " + treeStr, x + 14, ty, 0xFFFFAA00, body);
        ty += lineH;
        c.drawText("Runtime: " + formatRuntime(elapsed), x + 14, ty, 0xFFDDDDDD, body);
        ty += lineH;
        c.drawText("Logs burnt: " + logsBurnt, x + 14, ty, 0xFF66FF66, body);
        ty += lineH;
        long logsPerHour = (long) ((logsBurnt * 3_600_000D) / elapsed);
        c.drawText("Logs/hr: " + logsPerHour, x + 14, ty, 0xFF66CCFF, body);
        ty += lineH;
        ty += 8; // spacer
        c.drawText("Firemaking:", x + 14, ty, 0xFFFF8800, body);
        ty += lineH;
        if (fmXP != null) {
            long xpGained = (long) fmXP.getXpGained();
            c.drawText("  XP gained: " + formatNumber(xpGained), x + 14, ty, 0xFF66FF66, body);
            ty += lineH;

            long xpPerHour = fmXP.getXpPerHour();
            c.drawText("  XP/hr: " + formatNumber(xpPerHour), x + 14, ty, 0xFF66CCFF, body);
            ty += lineH;

            c.drawText("  Time to level: " + fmXP.timeToNextLevelString(), x + 14, ty, 0xFF66CCFF, body);
            ty += lineH;

            long currentXP = (long) fmXP.getXp();
            int currentLevel = fmXP.getLevel();

            if (currentLevel < 50) {
                long xpTo50 = XP_TO_50 - currentXP;
                int progressPercent = (int) ((currentXP * 100) / XP_TO_50);

                ty += 4;
                drawProgressBar(c, x + 14, ty, w - 28, 20, progressPercent, "Progress to 50", 0xFFFF8800);
                ty += 28 + 4;

                if (xpPerHour > 0) {
                    long totalSecondsTo50 = (xpTo50 * 3600) / xpPerHour;
                    long hoursTo50 = totalSecondsTo50 / 3600;
                    long minutesTo50 = (totalSecondsTo50 % 3600) / 60;
                    long secondsTo50 = totalSecondsTo50 % 60;
                    String timeTo50 = String.format("%02d:%02d:%02d", hoursTo50, minutesTo50, secondsTo50);
                    c.drawText("  Time to 50: " + timeTo50, x + 14, ty, 0xFFFFD700, body);
                } else {
                    c.drawText("  Time to 50: Calculating...", x + 14, ty, 0xFFFFD700, body);
                }
                ty += lineH;
            } else {
                c.drawText("  Level 50 complete!", x + 14, ty, 0xFFFFD700, body);
                ty += lineH;
            }
        } else {
            long estXP = (long) logsBurnt * EST_XP_PER_LOG;
            c.drawText("  XP (est.): ~" + formatNumber(estXP), x + 14, ty, 0xFF66FF66, body);
            ty += lineH;
            long estXpPerHour = (long) ((estXP * 3_600_000D) / elapsed);
            c.drawText("  XP/hr (est.): ~" + formatNumber(estXpPerHour), x + 14, ty, 0xFF66CCFF, body);
            ty += lineH;
            int progressPercent = (int) Math.min(100, (estXP * 100) / XP_TO_50);
            ty += 4;
            drawProgressBar(c, x + 14, ty, w - 28, 20, progressPercent, "Progress to 50 (est.)", 0xFFFF8800);
            ty += 28 + 4;
            c.drawText("  (tracker unavailable)", x + 14, ty, 0xFF888888, body);
            ty += lineH;
        }

        ty += 8; // spacer

        c.drawText("Woodcutting:", x + 14, ty, 0xFF00AA00, body);
        ty += lineH;

        if (wcXP != null) {
            long xpGained = (long) wcXP.getXpGained();
            c.drawText("  XP gained: " + formatNumber(xpGained), x + 14, ty, 0xFF66FF66, body);
            ty += lineH;

            long xpPerHour = wcXP.getXpPerHour();
            c.drawText("  XP/hr: " + formatNumber(xpPerHour), x + 14, ty, 0xFF66CCFF, body);
        } else {
            c.drawText("  XP gained: --", x + 14, ty, 0xFF66FF66, body);
            ty += lineH;
            c.drawText("  XP/hr: --", x + 14, ty, 0xFF66CCFF, body);
        }
    }

    private void drawHeader(Canvas c, String author, String title, int x, int y) {
        Font authorFont = new Font("Segoe UI", Font.PLAIN, 16);
        Font titleFont = new Font("Segoe UI", Font.BOLD, 20);

        // Shadow
        c.drawText(author, x + 1, y + 1, 0xAA000000, authorFont);
        c.drawText(title, x + 1, y + 26, 0xAA000000, titleFont);

        // Main text
        c.drawText(author, x, y, 0xFFB0B0B0, authorFont);
        c.drawText(title, x, y + 25, 0xFFD0D0D0, titleFont);

        // Highlight
        c.drawText(title, x - 1, y + 24, 0xFFFFFFFF, titleFont);
    }


    private void drawProgressBar(Canvas c, int x, int y, int width, int height, int percent, String label, int color) {
        // Background
        c.fillRect(x, y, width, height, new Color(40, 40, 40).getRGB());

        // Fill
        if (percent > 0) {
            int fillWidth = (int) (width * (Math.min(100, percent) / 100.0));
            c.fillRect(x, y, fillWidth, height, color);
        }

        // Border
        c.drawRect(x, y, width, height, new Color(100, 100, 100).getRGB());

        // Text
        Font barFont = new Font("Arial", Font.BOLD, 11);
        String text = label + " (" + percent + "%)";
        FontMetrics fm = c.getFontMetrics(barFont);
        int textX = x + (width - fm.stringWidth(text)) / 2;
        int textY = y + (height + fm.getAscent()) / 2 - 2;
        c.drawText(text, textX, textY, Color.WHITE.getRGB(), barFont);
    }

    private String formatRuntime(long milliseconds) {
        long totalSeconds = milliseconds / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    private String formatNumber(long number) {
        return String.format("%,d", number);
    }
}
