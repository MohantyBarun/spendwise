package com.spendwise.app.core;

import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds the transaction date inside a bank SMS (used when the SMS was shared, so its receive time is unknown). */
public final class SmsDates {
    private SmsDates() {}

    private static final String MONTHS = "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec";
    private static final Pattern ISO = Pattern.compile("\\b(20\\d{2})-(\\d{1,2})-(\\d{1,2})");
    private static final Pattern NAMED = Pattern.compile(
            "\\b(\\d{1,2})[- ]?(" + MONTHS + ")[a-z]*[- ,]*(\\d{4}|\\d{2})(?!\\d)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMERIC = Pattern.compile("\\b(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4}|\\d{2})(?![\\d.])");

    /**
     * @return the date found in the text (at noon, or at the current time if it is today), or {@code now}
     *         when there is no plausible date in the last 400 days
     */
    public static long find(String text, long now) {
        Matcher m = ISO.matcher(text);
        if (m.find()) return pick(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)), now);
        m = NAMED.matcher(text);
        if (m.find()) {
            int month = (MONTHS.indexOf(m.group(2).toLowerCase(Locale.ROOT)) / 4) + 1;
            return pick(Integer.parseInt(m.group(1)), month, year(m.group(3)), now);
        }
        m = NUMERIC.matcher(text);
        if (m.find()) return pick(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), year(m.group(3)), now);
        return now;
    }

    private static int year(String y) {
        int v = Integer.parseInt(y);
        return y.length() == 2 ? 2000 + v : v;
    }

    private static long pick(int day, int month, int year, long now) {
        if (month < 1 || month > 12 || day < 1 || day > 31) return now;
        Calendar c = Calendar.getInstance();
        c.setLenient(false);
        c.clear();
        c.set(year, month - 1, day, 12, 0, 0);
        long t;
        try {
            t = c.getTimeInMillis();
        } catch (IllegalArgumentException invalid) { // e.g. 31 Feb
            return now;
        }
        Calendar today = Calendar.getInstance();
        today.setTimeInMillis(now);
        if (today.get(Calendar.YEAR) == year && today.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)) return now;
        if (t > now || t < now - 400L * 86_400_000L) return now;
        return t;
    }
}
