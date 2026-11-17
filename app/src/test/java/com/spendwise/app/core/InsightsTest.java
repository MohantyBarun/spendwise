package com.spendwise.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.spendwise.app.model.Transaction;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class InsightsTest {
    /** Monday 9 Nov 2026, 00:00 local time. */
    private static long monday() {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.NOVEMBER, 9, 0, 0, 0);
        return c.getTimeInMillis();
    }

    private static Transaction txn(long dayStart, int hour, double amount, String merchant, String category, String note) {
        Transaction t = new Transaction();
        t.timestamp = dayStart + hour * 3_600_000L;
        t.amount = amount;
        t.merchant = merchant;
        t.category = category;
        t.note = note;
        return t;
    }

    private static boolean anyTip(Insights.Report r, String fragment) {
        for (String tip : r.tips) if (tip.contains(fragment)) return true;
        return false;
    }

    @Test
    public void weekStartIsMondayMidnight() {
        long mon = monday();
        assertEquals(mon, Insights.weekStart(mon));
        assertEquals(mon, Insights.weekStart(mon + 3_600_000L));
        assertEquals(mon, Insights.weekStart(Insights.addDays(mon, 6) + 23 * 3_600_000L)); // Sunday night
        assertEquals(Insights.addDays(mon, 7), Insights.weekStart(Insights.addDays(mon, 7)));
        assertEquals(Insights.addDays(mon, -7), Insights.weekStart(mon - 1));
    }

    @Test
    public void totalsCategoriesAndTips() {
        long mon = monday();
        List<Transaction> all = new ArrayList<>();
        // This week: 5 food orders of 300, one 2000 shopping, 5 small chai payments with no note.
        for (int d = 0; d < 5; d++) all.add(txn(Insights.addDays(mon, d), 20, 300, "SWIGGY", Categorizer.FOOD, "dinner"));
        all.add(txn(Insights.addDays(mon, 2), 15, 2000, "AMAZON", "Shopping", "headphones"));
        for (int d = 0; d < 5; d++) all.add(txn(Insights.addDays(mon, d), 10, 20, "chaiwala@okaxis", Categorizer.UNCATEGORISED, ""));
        // Last week: 600 food, 400 shopping. Three weeks ago: 600 food. Outside history: ignored.
        all.add(txn(Insights.addDays(mon, -3), 12, 600, "ZOMATO", Categorizer.FOOD, "lunch"));
        all.add(txn(Insights.addDays(mon, -4), 12, 400, "FLIPKART", "Shopping", "cable"));
        all.add(txn(Insights.addDays(mon, -17), 12, 600, "ZOMATO", Categorizer.FOOD, "lunch"));
        all.add(txn(Insights.addDays(mon, -40), 12, 99999, "OLD", "Other", "old"));
        all.add(txn(Insights.addDays(mon, 7), 1, 5000, "NEXT WEEK", "Other", "future"));

        Insights.Report r = Insights.build(all, mon, 12000);

        assertEquals(3600.0, r.total, 0.001);
        assertEquals(11, r.count);
        assertEquals(5, r.pending);
        assertEquals(1000.0, r.prevTotal, 0.001);
        assertEquals(800.0, r.usualWeekly, 0.001); // (1000 + 600) / 2 weeks with data

        assertEquals("Shopping", r.byCategory.keySet().iterator().next());
        assertEquals(1500.0, r.byCategory.get(Categorizer.FOOD), 0.001);
        assertEquals("AMAZON", r.topMerchants.keySet().iterator().next());

        assertTrue(anyTip(r, "260% more than last week"));
        assertTrue("food 1500 vs usual 600", anyTip(r, "Food & Dining is ₹900 above your usual ₹600"));
        assertTrue(anyTip(r, "You paid for food 5 times"));
        assertTrue(anyTip(r, "5 small payments"));
        assertTrue("budget 12000 => 2800/week, 3600 is over", anyTip(r, "over your ₹12,000 budget"));
        assertTrue(anyTip(r, "5 payments still need a description"));
        assertFalse("weekdays only", anyTip(r, "Saturday and Sunday"));
    }

    @Test
    public void weekInProgressComparesLikeForLike() {
        long mon = monday();
        List<Transaction> all = new ArrayList<>();
        // Last week: 300 on Monday, 3000 on Friday. This week (viewed Tuesday evening): 500 on Monday.
        all.add(txn(Insights.addDays(mon, -7), 12, 300, "SWIGGY", Categorizer.FOOD, "lunch"));
        all.add(txn(Insights.addDays(mon, -3), 12, 3000, "AMAZON", "Shopping", "shoes"));
        all.add(txn(mon, 12, 500, "ZOMATO", Categorizer.FOOD, "lunch"));
        long tuesdayEvening = Insights.addDays(mon, 1) + 19 * 3_600_000L;

        Insights.Report r = Insights.build(all, mon, 3000, tuesdayEvening);
        assertEquals(2, r.daysElapsed);
        assertEquals(300.0, r.prevSamePoint, 0.001);
        assertEquals(3300.0, r.prevTotal, 0.001);
        assertTrue("500 vs 300 by Tuesday is +67%, not -85% vs the whole of last week",
                anyTip(r, "2 days in, you have spent ₹500, 67% more than ₹300"));
        assertFalse(anyTip(r, "down"));
        // Budget 3000/month => 200 allowed for 2 days; 500 is over pace.
        assertTrue(anyTip(r, "At this pace you will spend about ₹7,500 this month"));

        Insights.Report done = Insights.build(all, mon, 0, Insights.addDays(mon, 9));
        assertEquals(7, done.daysElapsed);
        assertTrue(anyTip(done, "spending is down 85% from last week"));
    }

    @Test
    public void emptyWeekAndNoHistory() {
        long mon = monday();
        Insights.Report empty = Insights.build(new ArrayList<>(), mon, 0);
        assertEquals(0, empty.count);
        assertEquals(1, empty.tips.size());

        List<Transaction> one = new ArrayList<>();
        one.add(txn(Insights.addDays(mon, 5), 12, 500, "PVR", "Entertainment", "movie")); // Saturday
        Insights.Report r = Insights.build(one, mon, 0);
        assertEquals(500.0, r.total, 0.001);
        assertEquals(0.0, r.usualWeekly, 0.001);
        assertTrue(anyTip(r, "Set a monthly budget"));
        assertFalse(anyTip(r, "last week"));
    }
}
