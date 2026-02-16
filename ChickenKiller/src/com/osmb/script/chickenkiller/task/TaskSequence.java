package com.osmb.script.chickenkiller.task;

import com.osmb.api.script.Script;

import java.util.List;

public class TaskSequence {
    private final Script script;
    private final List<Task> tasks;
    private Task currentTask;

    public TaskSequence(Script script, List<Task> tasks) {
        this.script = script;
        this.tasks = tasks;
    }

    public boolean execute() {
        for (Task task : tasks) {
            if (script.stopped()) return false;

            if (!task.canExecute()) {
                script.log(getClass(), "Skipping " + task);
                continue;
            }

            currentTask = task;
            script.log(getClass(), "Starting " + task);

            boolean success = task.execute();

            currentTask = null;

            if (!success) {
                script.log(getClass(), "Task failed: " + task);
            }
        }
        return true;
    }

    public Task getCurrentTask() {
        return currentTask;
    }
}
