package com.osmb.script.chickenkiller;

import com.osmb.api.script.Script;
import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.script.SkillCategory;
import com.osmb.api.utils.RandomUtils;
import com.osmb.api.visual.drawing.Canvas;
import com.osmb.script.chickenkiller.task.*;

import java.awt.*;
import java.util.List;

@ScriptDefinition(
        author = "Sainty",
        name = "Chicken Killer 9000",
        threadUrl = "https://wiki.osmb.co.uk/article/chicken-feather-farmer",
        skillCategory = SkillCategory.COMBAT,
        version = 3.0
)
public class ChickenScript extends Script {

    private long scriptStartTime;
    private int feathersCollected = 0;
    private int chickensKilled = 0;
    private TaskSequence taskSequence;

    public ChickenScript(Object scriptCore) {
        super(scriptCore);
    }

    @Override
    public void onStart() {
        scriptStartTime = System.currentTimeMillis();
        log(getClass(), "Starting chicken farming");

        taskSequence = new TaskSequence(this, List.of(
                new WalkToCoopTask(this),
                new DropItemsTask(this),
                new LootFeathersTask(this),
                new KillChickenTask(this)
        ));
    }

    @Override
    public int poll() {
        if (taskSequence == null) {
            return RandomUtils.gaussianRandom(200, 1000, 400, 100);
        }

        taskSequence.execute();
        updateStats();

        return RandomUtils.gaussianRandom(300, 1000, 500, 100);
    }

    public void incrementFeathers(int amount) {
        feathersCollected += amount;
    }

    public void incrementKills() {
        chickensKilled++;
    }

    private void updateStats() {
    }

    private void drawHeader(Canvas c, String author, String title, int x, int y) {
        Font authorFont = new Font("Segoe UI", Font.PLAIN, 16);
        Font titleFont = new Font("Segoe UI", Font.BOLD, 20);
        c.drawText(author, x + 1, y + 1, 0xAA000000, authorFont);
        c.drawText(title, x + 1, y + 25 + 1, 0xAA000000, titleFont);
        c.drawText(author, x, y, 0xFFB0B0B0, authorFont);
        c.drawText(title, x, y + 25, 0xFFD0D0D0, titleFont);
        c.drawText(title, x - 1, y + 24, 0xFFFFFFFF, titleFont);
    }

    @Override
    public void onPaint(Canvas c) {
        int x = 16;
        int y = 40;
        int w = 240;
        int headerH = 45;
        int bodyH = 110;
        int BG = new Color(12, 14, 20, 235).getRGB();
        int BORDER = new Color(100, 100, 110, 180).getRGB();
        int DIVIDER = new Color(255, 255, 255, 40).getRGB();
        Font bodyFont = new Font("Segoe UI", Font.PLAIN, 13);

        c.fillRect(x, y, w, headerH + bodyH, BG, 1);
        c.drawRect(x, y, w, headerH + bodyH, BORDER);
        drawHeader(c, "Sainty", "Chicken Killer", x + 14, y + 16);
        c.fillRect(x + 10, y + headerH, w - 20, 1, DIVIDER);

        int ty = y + headerH + 18;
        c.drawText("Runtime: " + formatRuntime(), x + 14, ty, 0xFFFFFFFF, bodyFont);
        ty += 14;
        c.drawText("Chickens: " + chickensKilled, x + 14, ty, 0xFF66FF66, bodyFont);
        ty += 14;
        c.drawText("Feathers: " + feathersCollected, x + 14, ty, 0xFF66CCFF, bodyFont);
        ty += 14;

        long elapsed = System.currentTimeMillis() - scriptStartTime;
        if (elapsed > 0) {
            double hours = elapsed / 3_600_000.0;
            int killsPerHour = hours > 0 ? (int) (chickensKilled / hours) : 0;
            int feathersPerHour = hours > 0 ? (int) (feathersCollected / hours) : 0;
            c.drawText("Kills/hr: " + killsPerHour, x + 14, ty, 0xFFAAFFAA, bodyFont);
            ty += 14;
            c.drawText("Feathers/hr: " + feathersPerHour, x + 14, ty, 0xFFFFAA00, bodyFont);
            ty += 14;
        }

        if (taskSequence != null) {
            Task currentTask = taskSequence.getCurrentTask();
            if (currentTask != null) {
                c.drawText("Task: " + currentTask, x + 14, ty, 0xFFFFAA00, bodyFont);
            }
        }
    }

    private String formatRuntime() {
        long ms = System.currentTimeMillis() - scriptStartTime;
        long s = ms / 1000;
        long m = s / 60;
        long h = m / 60;
        return String.format("%02d:%02d:%02d", h, m % 60, s % 60);
    }

    @Override
    public int[] regionsToPrioritise() {
        return new int[]{12850, 12851, 12595};
    }
}
