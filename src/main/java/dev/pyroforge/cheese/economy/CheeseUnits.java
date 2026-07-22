package dev.pyroforge.cheese.economy;

/** 1 nugget = 1 unit, 1 ingot = 9 units. All economy math is done in units to avoid rounding. */
public final class CheeseUnits {

    public static final long NUGGET_UNITS = 1;
    public static final long INGOT_UNITS = 9;

    private CheeseUnits() {
    }

    public static long ingotsToUnits(long ingots) {
        return ingots * INGOT_UNITS;
    }

    public static long nuggetsToUnits(long nuggets) {
        return nuggets * NUGGET_UNITS;
    }
}
