package com.spendwise.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.spendwise.app.model.Transaction;

import org.junit.Test;

public class SmsParserTest {
    private static final long TS = 1_794_900_000_000L;

    @Test
    public void hdfcUpi() {
        Transaction t = SmsParser.parse("VM-HDFCBK",
                "Sent Rs.250.00\nFrom HDFC Bank A/C *1234\nTo SWIGGY\nOn 17/11/26\nRef 431234567890\n"
                        + "Not You?\nCall 18002586161/SMS BLOCK UPI to 7308080808", TS);
        assertNotNull(t);
        assertEquals(250.0, t.amount, 0.001);
        assertEquals("SWIGGY", t.merchant);
        assertEquals("1234", t.accountLast4);
        assertEquals("431234567890", t.refNo);
        assertEquals(Categorizer.FOOD, t.category);
    }

    @Test
    public void sbiUpiWithoutCurrencyPrefix() {
        Transaction t = SmsParser.parse("AD-SBIUPI",
                "Dear UPI user A/C X1234 debited by 235.0 on date 17Nov26 trf to RAMESH KUMAR "
                        + "Refno 431234567891. If not u? call 1800111109. -SBI", TS);
        assertNotNull(t);
        assertEquals(235.0, t.amount, 0.001);
        assertEquals("RAMESH KUMAR", t.merchant);
        assertEquals("431234567891", t.refNo);
        assertEquals(Categorizer.UNCATEGORISED, t.category);
    }

    @Test
    public void iciciDebitThatMentionsCredited() {
        Transaction t = SmsParser.parse("JD-ICICIB",
                "ICICI Bank Acct XX123 debited for Rs 349.00 on 17-Nov-26; ZOMATO credited. "
                        + "UPI:432112345678. Call 18002662 for dispute. SMS BLOCK 123 to 9215676766.", TS);
        assertNotNull(t);
        assertEquals(349.0, t.amount, 0.001);
        assertEquals("ZOMATO", t.merchant);
        assertEquals("123", t.accountLast4);
        assertEquals("432112345678", t.refNo);
    }

    @Test
    public void axisMultiLine() {
        Transaction t = SmsParser.parse("AX-AXISBK",
                "INR 500.00 debited\nA/c no. XX1234\n17-11-26, 12:00:00\nUPI/P2M/431234567892/BIG BASKET\n"
                        + "Not you? SMS BLOCKUPI Cust ID to 919951860002\nAxis Bank", TS);
        assertNotNull(t);
        assertEquals(500.0, t.amount, 0.001);
        assertEquals("BIG BASKET", t.merchant);
        assertEquals("1234", t.accountLast4);
        assertEquals("431234567892", t.refNo);
        assertEquals("Groceries", t.category);
    }

    @Test
    public void kotakVpa() {
        Transaction t = SmsParser.parse("VK-KOTAKB",
                "Sent Rs.120.00 from Kotak Bank AC X1234 to chaiwala@okaxis on 17-11-26.UPI Ref 431234567894. "
                        + "Not you, https://kotak.com/KBANKT/Fraud", TS);
        assertNotNull(t);
        assertEquals(120.0, t.amount, 0.001);
        assertEquals("chaiwala@okaxis", t.merchant);
        assertEquals("431234567894", t.refNo);
    }

    @Test
    public void creditCardSpendWithLakhGrouping() {
        Transaction t = SmsParser.parse("VM-HDFCBK",
                "Rs 1,29,999.00 spent on HDFC Bank Card x4321 at AMAZON on 2026-11-17:12:00:00. "
                        + "Not you? To block call 18002586161", TS);
        assertNotNull(t);
        assertEquals(129999.0, t.amount, 0.001);
        assertEquals("AMAZON", t.merchant);
        assertEquals("4321", t.accountLast4);
        assertEquals("Shopping", t.category);
    }

    @Test
    public void ignoresCreditsOtpsRemindersAndPeople() {
        assertNull(SmsParser.parse("VM-HDFCBK",
                "Rs.5000.00 credited to a/c XX1234 on 17-11-26 by a/c linked to VPA friend@okaxis "
                        + "(UPI Ref no 431234567893).", TS));
        assertNull(SmsParser.parse("VM-HDFCBK",
                "123456 is OTP for txn of INR 500.00 at AMAZON on card x4321. Do not share.", TS));
        assertNull(SmsParser.parse("VM-HDFCBK",
                "Rs 199.00 will be debited from A/c XX1234 on 20-11-26 for Netflix mandate.", TS));
        assertNull(SmsParser.parse("VM-HDFCBK",
                "Your UPI txn of Rs 300 to SWIGGY has failed. Amount if debited will be refunded.", TS));
        assertNull(SmsParser.parse("+919876543210",
                "Rs 500 debited from your a/c XX1234 to SCAMMER. Click http://bad.example", TS));
        assertNull(SmsParser.parse("VM-HDFCBK", "Get 10% off on your next order!", TS));
    }

    @Test
    public void idIsStableAndDistinct() {
        String body = "Sent Rs.250.00\nFrom HDFC Bank A/C *1234\nTo SWIGGY\nOn 17/11/26\nRef 431234567890";
        Transaction a = SmsParser.parse("VM-HDFCBK", body, TS);
        Transaction b = SmsParser.parse("VM-HDFCBK", body, TS + 5000);
        assertEquals("same ref number must dedupe even if timestamps differ", a.id, b.id);

        String noRef = "Rs 20 debited from a/c XX1234 to TEA STALL";
        Transaction c = SmsParser.parse("VM-HDFCBK", noRef, TS);
        Transaction d = SmsParser.parse("VM-HDFCBK", noRef, TS + 60_000);
        assertNotEquals("two identical no-ref payments at different times are different expenses", c.id, d.id);
        assertEquals("TEA STALL", c.merchant);
    }
}
