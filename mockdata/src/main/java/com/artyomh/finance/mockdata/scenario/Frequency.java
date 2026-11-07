package com.artyomh.finance.mockdata.scenario;

/**
 * How many times a variable expense occurs in a month, and the chance that it occurs at all.
 */
public record Frequency(int minTimes, int maxTimes, int chancePercent) {

    private static final int ALWAYS = 100;

    public Frequency {
        if (minTimes < 0 || maxTimes < minTimes) {
            throw new IllegalArgumentException("times must satisfy 0 <= min <= max: " + minTimes + ".." + maxTimes);
        }
        if (chancePercent < 1 || chancePercent > ALWAYS) {
            throw new IllegalArgumentException("chance must be 1.." + ALWAYS + " percent: " + chancePercent);
        }
    }

    public static Frequency times(int minTimes, int maxTimes) {
        return new Frequency(minTimes, maxTimes, ALWAYS);
    }

    public static Frequency oncePerMonthWithChance(int chancePercent) {
        return new Frequency(1, 1, chancePercent);
    }
}
