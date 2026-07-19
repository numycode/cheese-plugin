package dev.pyroforge.cheese.scan;

import java.util.LinkedHashMap;
import java.util.Map;

/** Accumulates scan totals broken down by world and by container category. */
public final class ScanResult {

    private final Map<String, Long> unitsByWorld = new LinkedHashMap<>();
    private final Map<String, Long> unitsByCategory = new LinkedHashMap<>();
    private long total;

    public void add(String world, String category, long units) {
        if (units == 0) {
            return;
        }
        total += units;
        unitsByWorld.merge(world, units, Long::sum);
        unitsByCategory.merge(category, units, Long::sum);
    }

    public long getTotal() {
        return total;
    }

    public Map<String, Long> getUnitsByWorld() {
        return unitsByWorld;
    }

    public Map<String, Long> getUnitsByCategory() {
        return unitsByCategory;
    }

    public String toReportString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Cheese scan: ").append(total).append(" units total\n");
        sb.append("  By world:\n");
        unitsByWorld.forEach((world, units) -> sb.append("    ").append(world).append(": ").append(units).append('\n'));
        sb.append("  By category:\n");
        unitsByCategory.forEach((category, units) -> sb.append("    ").append(category).append(": ").append(units).append('\n'));
        return sb.toString();
    }
}
