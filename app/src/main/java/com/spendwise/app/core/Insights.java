package com.spendwise.app.core;

import com.spendwise.app.model.Transaction;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Weekly summary and rule-based saving tips. Computed on the phone, so it costs nothing to run. */
public final class Insights {
    private Insights() {}

    /** How many earlier weeks are used as the "usual" baseline. */
    public static final int HISTORY_WEEKS = 4;

    public static class Report {
        public long weekStart;
        public long weekEnd;
        public double total;
        public double prevTotal;
        /** Days of this week that have started (1-7). Below 7 the week is still in progress. */
        public int daysElapsed = 7;
        /** Last week's spending over the same number of days, for like-for-like comparison mid-week. */
        public double prevSamePoint;
        /** Average of the earlier weeks that have any data; 0 when there is no history. */
        public double usualWeekly;
        public int count;
        public int pending;
        public LinkedHashMap<String, Double> byCategory = new LinkedHashMap<>();
        public LinkedHashMap<String, Double> topMerchants = new LinkedHashMap<>();
        public List<String> tips = new ArrayList<>();
    }

    /** Monday 00:00 (device time zone) of the week containing t. */
    public static long weekStart(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_MONTH, -((c.get(Calendar.DAY_OF_WEEK) + 5) % 7));
        return c.getTimeInMillis();
    }

    public static long addDays(long t, int days) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.add(Calendar.DAY_OF_MONTH, days);
        return c.getTimeInMillis();
    }

    /** Earliest timestamp build() needs for the given week. */
    public static long historyStart(long weekStart) {
        return addDays(weekStart, -7 * HISTORY_WEEKS);
    }

    /**
     * @param all           transactions covering at least [historyStart(weekStart), weekStart + 7 days)
     * @param monthlyBudget 0 if the user has not set one
     */
    public static Report build(List<Transaction> all, long weekStart, double monthlyBudget) {
        return build(all, weekStart, monthlyBudget, Long.MAX_VALUE);
    }

    /** @param now current time; when it falls inside the week, comparisons and budget pace are pro-rated. */
    public static Report build(List<Transaction> all, long weekStart, double monthlyBudget, long now) {
        Report r = new Report();
        r.weekStart = weekStart;
        r.weekEnd = addDays(weekStart, 7);
        for (int d = 1; d <= 7; d++) {
            if (addDays(weekStart, d) > now) {
                r.daysElapsed = d;
                break;
            }
        }
        boolean partial = r.daysElapsed < 7;
        long samePointEnd = addDays(weekStart, -7 + r.daysElapsed);

        double[] priorTotals = new double[HISTORY_WEEKS];
        List<Map<String, Double>> priorCats = new ArrayList<>();
        long[] priorStart = new long[HISTORY_WEEKS];
        for (int k = 0; k < HISTORY_WEEKS; k++) {
            priorStart[k] = addDays(weekStart, -7 * (k + 1));
            priorCats.add(new HashMap<>());
        }

        Map<String, Double> cats = new HashMap<>();
        Map<String, Double> merchants = new HashMap<>();
        Map<String, Integer> merchantCount = new HashMap<>();
        int foodCount = 0, smallCount = 0;
        double foodTotal = 0, smallTotal = 0, weekendTotal = 0, uncategorised = 0;

        for (Transaction t : all) {
            if (t.timestamp >= weekStart && t.timestamp < r.weekEnd) {
                r.total += t.amount;
                r.count++;
                if (t.needsDescription()) r.pending++;
                cats.merge(t.category, t.amount, Double::sum);
                String m = t.merchant == null || t.merchant.isEmpty() ? "Unknown" : t.merchant;
                merchants.merge(m, t.amount, Double::sum);
                merchantCount.merge(m, 1, Integer::sum);
                if (Categorizer.FOOD.equals(t.category)) { foodCount++; foodTotal += t.amount; }
                if (Categorizer.UNCATEGORISED.equals(t.category)) uncategorised += t.amount;
                if (t.amount <= 100) { smallCount++; smallTotal += t.amount; }
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(t.timestamp);
                int dow = c.get(Calendar.DAY_OF_WEEK);
                if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) weekendTotal += t.amount;
            } else {
                for (int k = 0; k < HISTORY_WEEKS; k++) {
                    long end = k == 0 ? weekStart : priorStart[k - 1];
                    if (t.timestamp >= priorStart[k] && t.timestamp < end) {
                        priorTotals[k] += t.amount;
                        if (k == 0 && t.timestamp < samePointEnd) r.prevSamePoint += t.amount;
                        priorCats.get(k).merge(t.category, t.amount, Double::sum);
                        break;
                    }
                }
            }
        }

        r.prevTotal = priorTotals[0];
        int weeksWithData = 0;
        double priorSum = 0;
        for (double p : priorTotals) if (p > 0) { weeksWithData++; priorSum += p; }
        r.usualWeekly = weeksWithData == 0 ? 0 : priorSum / weeksWithData;

        r.byCategory = sortedDesc(cats, Integer.MAX_VALUE);
        r.topMerchants = sortedDesc(merchants, 5);

        if (r.count == 0) {
            r.tips.add("No expenses recorded this week. If you did spend, check that SMS permission is on and try \"Scan SMS inbox\".");
            return r;
        }

        // 1. Week-on-week trend (mid-week: against last week up to the same day)
        if (partial) {
            if (r.prevSamePoint > 0) {
                double change = (r.total - r.prevSamePoint) / r.prevSamePoint * 100;
                if (change >= 15) {
                    r.tips.add(String.format("%d days in, you have spent %s, %.0f%% more than %s by this point last week.",
                            r.daysElapsed, Money.fmt(r.total), change, Money.fmt(r.prevSamePoint)));
                } else if (change <= -10) {
                    r.tips.add(String.format("Nice: %d days in, spending is %.0f%% below last week at this point (%s vs %s).",
                            r.daysElapsed, -change, Money.fmt(r.total), Money.fmt(r.prevSamePoint)));
                }
            }
        } else if (r.prevTotal > 0) {
            double change = (r.total - r.prevTotal) / r.prevTotal * 100;
            if (change >= 15) {
                r.tips.add(String.format("You spent %.0f%% more than last week (%s vs %s).",
                        change, Money.fmt(r.total), Money.fmt(r.prevTotal)));
            } else if (change <= -10) {
                r.tips.add(String.format("Nice: spending is down %.0f%% from last week (%s vs %s).",
                        -change, Money.fmt(r.total), Money.fmt(r.prevTotal)));
            }
        }

        // 2. Categories running above their usual level
        if (weeksWithData > 0) {
            for (Map.Entry<String, Double> e : r.byCategory.entrySet()) {
                if (Categorizer.UNCATEGORISED.equals(e.getKey())) continue;
                double usual = 0;
                for (Map<String, Double> pc : priorCats) usual += pc.getOrDefault(e.getKey(), 0.0);
                usual /= weeksWithData;
                double extra = e.getValue() - usual;
                if (usual > 0 && e.getValue() >= usual * 1.3 && extra >= 200) {
                    r.tips.add(String.format(
                            "%s is %s above your usual %s a week. Holding it at the usual level saves about %s a month.",
                            e.getKey(), Money.fmt(extra), Money.fmt(usual), Money.fmt(extra * 4)));
                }
            }
        }

        // 3. Eating out / ordering in
        if (foodCount >= 4) {
            double avg = foodTotal / foodCount;
            r.tips.add(String.format(
                    "You paid for food %d times (%s, about %s each). Skipping two of those a week saves roughly %s a month.",
                    foodCount, Money.fmt(foodTotal), Money.fmt(avg), Money.fmt(avg * 2 * 4)));
        }

        // 4. Small payments that add up
        if (smallCount >= 5) {
            r.tips.add(String.format(
                    "%d small payments of ₹100 or less added up to %s. These are the easiest to overlook.",
                    smallCount, Money.fmt(smallTotal)));
        }

        // 5. One merchant dominating
        if (!r.topMerchants.isEmpty()) {
            Map.Entry<String, Double> top = r.topMerchants.entrySet().iterator().next();
            double share = top.getValue() / r.total * 100;
            if (share >= 30 && merchantCount.getOrDefault(top.getKey(), 0) >= 2 && !"Unknown".equals(top.getKey())) {
                r.tips.add(String.format("%s (%.0f%% of the week) went to %s across %d payments.",
                        Money.fmt(top.getValue()), share, top.getKey(), merchantCount.get(top.getKey())));
            }
        }

        // 6. Weekend-heavy spending
        if (r.count >= 4 && weekendTotal >= r.total * 0.5) {
            r.tips.add(String.format(
                    "%s of %s was spent on Saturday and Sunday. Deciding a weekend amount in advance helps here.",
                    Money.fmt(weekendTotal), Money.fmt(r.total)));
        }

        // 7. Budget pace, pro-rated to the days elapsed
        if (monthlyBudget > 0) {
            double weeklyAllowance = monthlyBudget * 7 / 30;
            double allowanceSoFar = monthlyBudget * r.daysElapsed / 30;
            double projected = r.total / r.daysElapsed * 30;
            if (r.total > allowanceSoFar) {
                r.tips.add(String.format(
                        "At this pace you will spend about %s this month, %s over your %s budget. Aim for %s a week.",
                        Money.fmt(projected), Money.fmt(projected - monthlyBudget),
                        Money.fmt(monthlyBudget), Money.fmt(weeklyAllowance)));
            } else if (partial) {
                r.tips.add(String.format("On track: %s spent so far, within the %s allowance for %d days.",
                        Money.fmt(r.total), Money.fmt(allowanceSoFar), r.daysElapsed));
            } else {
                r.tips.add(String.format("On track: %s spent against a weekly allowance of %s.",
                        Money.fmt(r.total), Money.fmt(weeklyAllowance)));
            }
        } else {
            r.tips.add("Set a monthly budget from the menu to see whether each week is on pace.");
        }

        // 8. Data quality
        if (r.pending > 0) {
            r.tips.add(String.format(
                    "%d payment%s still need a description. Adding them makes these tips more accurate.",
                    r.pending, r.pending == 1 ? "" : "s"));
        }
        if (uncategorised >= r.total * 0.25) {
            r.tips.add(String.format("%s is uncategorised, so part of this week is a blind spot.",
                    Money.fmt(uncategorised)));
        }
        return r;
    }

    private static LinkedHashMap<String, Double> sortedDesc(Map<String, Double> in, int limit) {
        List<Map.Entry<String, Double>> list = new ArrayList<>(in.entrySet());
        list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        LinkedHashMap<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : list) {
            if (out.size() >= limit) break;
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }
}
