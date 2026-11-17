package com.spendwise.app.core;

import com.spendwise.app.model.Transaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Five weeks of realistic made-up spending, so the app and the weekly report can be shown to someone
 * without access to their real payments. The last three days are left without descriptions so the
 * reminder flow has something to show.
 */
public final class SampleData {
    private SampleData() {}

    public static List<Transaction> generate(long now, long seed) {
        Random r = new Random(seed);
        List<Transaction> out = new ArrayList<>();
        long today = Insights.addDays(Insights.weekStart(now), (int) ((now - Insights.weekStart(now)) / 86_400_000L));
        for (int back = 34; back >= 0; back--) {
            long day = Insights.addDays(today, -back);
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTimeInMillis(day);
            int dow = c.get(java.util.Calendar.DAY_OF_WEEK);
            boolean weekend = dow == java.util.Calendar.SATURDAY || dow == java.util.Calendar.SUNDAY;
            boolean recentWeek = back < 7;

            if (!weekend) add(out, r, day, 10, "chaiwala@okaxis", 15 + 5 * r.nextInt(4), "morning chai");
            if (!weekend && r.nextInt(10) < 6) add(out, r, day, 9, r.nextBoolean() ? "RAPIDO" : "UBER INDIA", 80 + r.nextInt(180), "ride to office");
            // Food ordering creeps up in the most recent week, which the report should notice.
            if (r.nextInt(10) < (recentWeek ? 8 : 4)) {
                add(out, r, day, 21, r.nextBoolean() ? "SWIGGY" : "ZOMATO", 180 + r.nextInt(300), "dinner order");
            }
            if (dow == java.util.Calendar.SUNDAY) add(out, r, day, 11, r.nextBoolean() ? "BLINKIT" : "BIG BASKET", 600 + r.nextInt(900), "weekly groceries");
            if (weekend && r.nextInt(10) < 4) add(out, r, day, 19, "PVR INOX", 350 + r.nextInt(400), "movie");
            if (weekend && r.nextInt(10) < 5) add(out, r, day, 14, "RAHUL SHARMA", 200 + 50 * r.nextInt(10), "split for lunch");
            if (r.nextInt(14) == 0) add(out, r, day, 16, "AMAZON", 499 + r.nextInt(2000), "household items");
            if (back == 20) add(out, r, day, 12, "AIRTEL", 299, "phone recharge");
            if (back == 12) add(out, r, day, 8, "NETFLIX", 199, "subscription");
            if (back == 15) add(out, r, day, 18, "APOLLO PHARMACY", 340 + r.nextInt(200), "medicines");
        }
        for (int i = 0; i < out.size(); i++) {
            Transaction t = out.get(i);
            t.id = "sample-" + i;
            if (t.timestamp > now) t.timestamp = now - (out.size() - i) * 60_000L;
            if (now - t.timestamp < 3 * 86_400_000L) {
                t.note = "";
                // Leave the uncategorised ones for the user to sort out.
                if (Categorizer.UNCATEGORISED.equals(Categorizer.guess(t.merchant, ""))) t.category = Categorizer.UNCATEGORISED;
            }
        }
        return out;
    }

    private static void add(List<Transaction> out, Random r, long day, int hour, String merchant, double amount, String note) {
        Transaction t = new Transaction();
        t.timestamp = day + hour * 3_600_000L + r.nextInt(50) * 60_000L;
        t.merchant = merchant;
        t.amount = amount;
        t.note = note;
        t.source = Transaction.SOURCE_SAMPLE;
        t.category = Categorizer.guess(merchant, note);
        if (merchant.equals("RAHUL SHARMA")) t.category = "Transfers";
        out.add(t);
    }
}
