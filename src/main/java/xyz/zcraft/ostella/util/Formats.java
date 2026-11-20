package xyz.zcraft.ostella.util;

import java.util.Locale;

public final class Formats {
    public static String formatStarRating(double starRating) {
        return String.format(Locale.ROOT, "%.2f", floorToDecimalDigits(starRating, 2));
    }

    public static String formatAccuracy(double accuracy) {
        return String.format(Locale.ROOT, "%.2f%%", floorToDecimalDigits(accuracy, 4) * 100);
    }

    private static double floorToDecimalDigits(double value, int digits) {
        double base10 = Math.pow(10, digits);
        return Math.floor(value * base10) / base10;
    }
}
