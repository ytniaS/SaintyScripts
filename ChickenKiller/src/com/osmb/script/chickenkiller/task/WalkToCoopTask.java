package com.osmb.script.chickenkiller.task;

import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.utils.RandomUtils;
import com.osmb.script.chickenkiller.ChickenScript;

public class WalkToCoopTask extends Task {
    private static final WorldPosition CHICKEN_COOP_CENTER = new WorldPosition(3230, 3297, 0);
    private static final int MAX_DISTANCE_FROM_COOP = 10;

    private final ChickenScript script;

    public WalkToCoopTask(ChickenScript script) {
        super(script);
        this.script = script;
    }

    @Override
    public boolean canExecute() {
        WorldPosition playerPosition = script.getWorldPosition();
        if (playerPosition == null) return false;
        return playerPosition.distanceTo(CHICKEN_COOP_CENTER) > MAX_DISTANCE_FROM_COOP;
    }

    @Override
    public boolean execute() {
        script.log(getClass(), "Walking to chicken coop");

        WorldPosition playerPosition = script.getWorldPosition();
        if (playerPosition == null) return false;

        script.getWalker().walkTo(CHICKEN_COOP_CENTER);

        long walkStartTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - walkStartTime < 90_000) {
            if (script.stopped()) return false;

            playerPosition = script.getWorldPosition();
            if (playerPosition == null) {
                script.pollFramesHuman(() -> true, RandomUtils.gaussianRandom(200, 400, 300, 50));
                continue;
            }

            if (playerPosition.distanceTo(CHICKEN_COOP_CENTER) <= MAX_DISTANCE_FROM_COOP) {
                return true;
            }

            script.pollFramesHuman(() -> true, RandomUtils.gaussianRandom(200, 400, 300, 50));
        }

        script.log(getClass(), "Timed out walking to coop");
        return false;
    }
}
