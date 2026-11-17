package com.spendwise.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.spendwise.app.model.Transaction;

import org.junit.Test;

import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SharedAndSampleTest {
    /** Tue 17 Nov 2026, 18:30 local. */
    private static long now() {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.NOVEMBER, 17, 18, 30, 0);
        return c.getTimeInMillis();
    }

    private static long noon(int y, int m, int d) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(y, m, d, 12, 0, 0);
        return c.getTimeInMillis();
    }

    @Test
    public void datesFromCommonBankFormats() {
        long now = now();
        assertEquals(noon(2026, Calendar.NOVEMBER, 14), SmsDates.find("Sent Rs.250.00 To SWIGGY On 14/11/26 Ref 4312", now));
        assertEquals(noon(2026, Calendar.NOVEMBER, 3), SmsDates.find("debited by 235.0 on date 03Nov26 trf to X", now));
        assertEquals(noon(2026, Calendar.OCTOBER, 30), SmsDates.find("debited for Rs 349.00 on 30-Oct-26; ZOMATO credited", now));
        assertEquals(noon(2026, Calendar.NOVEMBER, 2), SmsDates.find("spent at AMAZON on 2026-11-02:12:00:00", now));
        assertEquals(noon(2026, Calendar.NOVEMBER, 16), SmsDates.find("INR 500.00 debited\n16-11-2026, 12:00:00", now));
        assertEquals("today keeps the current time", now, SmsDates.find("Sent Rs.250.00 On 17/11/26", now));
        assertEquals("future date ignored", now, SmsDates.find("will run on 25/12/26", now));
        assertEquals("invalid date ignored", now, SmsDates.find("on 31/02/26", now));
        assertEquals("amounts are not dates", now, SmsDates.find("Sent Rs.250.00 to SWIGGY", now));
        assertEquals("no date", now, SmsDates.find("paid to SWIGGY", now));
    }

    @Test
    public void sharedSmsUsesDateFromTextAndSameIdAsAutoCapture() {
        String body = "Sent Rs.250.00\nFrom HDFC Bank A/C *1234\nTo SWIGGY\nOn 14/11/26\nRef 431234567890";
        Transaction shared = SmsParser.parseShared(body, now());
        assertNotNull(shared);
        assertEquals(Transaction.SOURCE_SHARED, shared.source);
        assertEquals(noon(2026, Calendar.NOVEMBER, 14), shared.timestamp);
        assertEquals(250.0, shared.amount, 0.001);
        Transaction auto = SmsParser.parse("VM-HDFCBK", body, noon(2026, Calendar.NOVEMBER, 14) + 1234);
        assertEquals("sharing an SMS that was already captured must not duplicate it", auto.id, shared.id);

        assertNull(SmsParser.parseShared("Get 10% off on your next order!", now()));
        assertNull(SmsParser.parseShared("123456 is OTP for txn of INR 500.00 at AMAZON", now()));
    }

    @Test
    public void sampleDataIsRealisticAndReportable() {
        long now = now();
        List<Transaction> sample = SampleData.generate(now, 42);
        assertTrue(sample.size() > 60);
        Set<String> ids = new HashSet<>();
        for (Transaction t : sample) {
            assertTrue(t.timestamp <= now);
            assertTrue(t.amount > 0);
            assertEquals(Transaction.SOURCE_SAMPLE, t.source);
            assertTrue(ids.add(t.id));
            if (now - t.timestamp >= 3 * 86_400_000L) assertFalse("older entries are described", t.needsDescription());
        }
        Insights.Report r = Insights.build(sample, Insights.weekStart(now), 20000);
        assertTrue(r.count > 0);
        assertTrue("recent payments need descriptions", r.pending > 0);
        assertTrue(r.usualWeekly > 0);
        assertTrue(r.tips.size() >= 3);
        assertEquals("deterministic", sample.size(), SampleData.generate(now, 42).size());
    }
}
