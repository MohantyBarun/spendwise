package com.spendwise.app.model;

/** One expense. Plain Java so the parsing and insight logic can be unit-tested off-device. */
public class Transaction {
    public static final String SOURCE_SMS = "SMS";
    public static final String SOURCE_MANUAL = "MANUAL";
    /** User shared or pasted an SMS into the app (works without SMS permission). */
    public static final String SOURCE_SHARED = "SHARED";
    /** Generated demo data; can be removed in one go. */
    public static final String SOURCE_SAMPLE = "SAMPLE";

    public String id;
    public double amount;
    public String merchant = "";
    public String note = "";
    public String category = "Uncategorised";
    public long timestamp;
    public String source = SOURCE_MANUAL;
    public String accountLast4 = "";
    public String refNo = "";
    public String sender = "";

    public boolean needsDescription() {
        return note == null || note.trim().isEmpty();
    }
}
