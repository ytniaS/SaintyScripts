package com.osmb.script.chickenkiller.task;

import com.osmb.api.script.Script;

public abstract class Task {
    protected final Script script;

    public Task(Script script) {
        this.script = script;
    }

    public abstract boolean canExecute();

    public abstract boolean execute();

    @Override
    public String toString() {
        String name = getClass().getSimpleName();
        if (name.endsWith("Task")) name = name.substring(0, name.length() - 4);
        return name.replaceAll("(?<!^)([A-Z])", " $1");
    }
}
