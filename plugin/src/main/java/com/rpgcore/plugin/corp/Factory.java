package com.rpgcore.plugin.corp;

/**
 * One plant a company owns: which kind it is and how far it has been upgraded.
 *
 * Everything else - what it makes, what it needs, what it costs to run - is
 * looked up from {@link CorpConfig.FactoryType}, so an operator retuning the
 * catalogue retunes every factory already standing rather than only new ones.
 */
public final class Factory {

    private final String typeId;
    private int level;
    /** Why it produced nothing last time, or null if it ran. For the screen. */
    private String idleReason;
    /** What it actually made on the last pass, for the screen. */
    private long lastOutput;

    public Factory(String typeId, int level) {
        this.typeId = typeId;
        this.level = Math.max(1, level);
    }

    public String typeId() {
        return typeId;
    }

    public int level() {
        return level;
    }

    void level(int level) {
        this.level = Math.max(1, level);
    }

    public String idleReason() {
        return idleReason;
    }

    void idleReason(String idleReason) {
        this.idleReason = idleReason;
    }

    public long lastOutput() {
        return lastOutput;
    }

    void lastOutput(long lastOutput) {
        this.lastOutput = lastOutput;
    }
}
