package com.spendwise.app.core;

import java.text.NumberFormat;
import java.util.Locale;

public final class Money {
    private Money() {}

    private static final Locale IN = new Locale("en", "IN");

    /** Rounded rupees with Indian digit grouping, e.g. ₹1,23,456. */
    public static String fmt(double v) {
        return "₹" + NumberFormat.getIntegerInstance(IN).format(Math.round(v));
    }

    /** Keeps paise when present, e.g. ₹249.50. */
    public static String exact(double v) {
        if (Math.abs(v - Math.round(v)) < 0.005) return fmt(v);
        NumberFormat f = NumberFormat.getNumberInstance(IN);
        f.setMinimumFractionDigits(2);
        f.setMaximumFractionDigits(2);
        return "₹" + f.format(v);
    }
}
