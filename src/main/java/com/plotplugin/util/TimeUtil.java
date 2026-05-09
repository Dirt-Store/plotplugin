package com.plotplugin.util;

public final class TimeUtil {

    private TimeUtil() {}

    public static String format(long millis) {
        if (millis <= 0) return "0m";
        long totalSeconds = millis / 1000;
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        sb.append(minutes).append("m");
        return sb.toString().trim();
    }

    public static long daysToMs(long days) {
        return days * 86400L * 1000L;
    }
}
