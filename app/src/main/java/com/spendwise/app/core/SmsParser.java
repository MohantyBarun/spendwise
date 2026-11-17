package com.spendwise.app.core;

import com.spendwise.app.model.Transaction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an Indian bank / UPI debit SMS into a Transaction.
 * Returns null for anything that is not a completed debit (OTPs, credits, reminders, promos).
 * Runs entirely on the phone: the SMS text itself is never stored or uploaded.
 */
public final class SmsParser {
    private SmsParser() {}

    private static final Pattern IGNORE = Pattern.compile(
            "\\b(otp|one time password|verification code|will be (?:debited|deducted|charged)|to be debited"
                    + "|is due|due on|due date|payment due|has requested|collect request|failed|declined"
                    + "|unsuccessful|not been debited|reversed|reversal)\\b");
    private static final Pattern DEBIT = Pattern.compile(
            "\\b(debited|spent|paid|sent|withdrawn|purchase|deducted|txn of)\\b");
    private static final Pattern CREDIT = Pattern.compile(
            "\\b(credited|received|deposited|refund(?:ed)?)\\b");

    private static final Pattern AMOUNT = Pattern.compile(
            "(?:\\brs\\.?|\\binr|\u20B9)\\s*(\\d[\\d,]*(?:\\.\\d{1,2})?)");
    private static final Pattern AMOUNT_BARE = Pattern.compile(
            "\\bdebited (?:by|for|with|of)\\s+(\\d[\\d,]*(?:\\.\\d{1,2})?)");

    private static final String NAME = "([A-Za-z][A-Za-z0-9@._&'\\- ]*?)";
    private static final String END =
            "(?=\\s+(?:on|ref|refno|upi|via|at|from|using|thru|through|if|not)\\b"
                    + "|\\s*[,;(\\n]|\\.(?:\\s|$)|\\s*$)";
    private static final int CI = Pattern.CASE_INSENSITIVE;
    private static final Pattern[] MERCHANT = {
            Pattern.compile(";\\s*([^;\\n]+?)\\s+credited", CI),
            Pattern.compile("UPI/P2[AMP]/\\d+/([^/\\n]+)", CI),
            Pattern.compile("\\b(?:trf to|transfer(?:red)? to|paid to|to vpa|to)\\s+" + NAME + END, CI),
            Pattern.compile("\\b(?:at|towards)\\s+" + NAME + END, CI),
    };

    private static final Pattern ACCOUNT = Pattern.compile(
            "\\b(?:a/c|ac(?:ct)?|account|card)\\s*(?:no\\.?|number|ending(?: with| in)?)?\\s*[:\\-]?\\s*[xX*]*\\s*(\\d{3,6})\\b",
            CI);
    private static final Pattern REF = Pattern.compile(
            "(?:ref(?:erence)?\\.?\\s*(?:no\\.?|number|id)?|rrn|utr|upi)\\s*[:.\\-]?\\s*(\\d{6,})", CI);
    private static final Pattern REF_UPI_PATH = Pattern.compile("UPI/P2[AMP]/(\\d{6,})", CI);

    public static Transaction parse(String sender, String body, long timestamp) {
        if (sender == null || body == null) return null;
        // Banks send from alphanumeric headers (e.g. VM-HDFCBK). A plain phone number is a person.
        if (!sender.matches(".*[A-Za-z].*")) return null;
        return parseBody(sender, body, timestamp, Transaction.SOURCE_SMS);
    }

    /**
     * For text the user shared or pasted into the app. The sender is unknown, so the date is taken
     * from the message itself when it contains one.
     */
    public static Transaction parseShared(String body, long now) {
        if (body == null) return null;
        if (body.length() > 2000) body = body.substring(0, 2000);
        return parseBody("Shared", body, SmsDates.find(body, now), Transaction.SOURCE_SHARED);
    }

    private static Transaction parseBody(String sender, String body, long timestamp, String source) {
        String text = body.replaceAll("[ \\t\\r]+", " ").trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (IGNORE.matcher(lower).find()) return null;

        int debitAt = firstIndex(DEBIT, lower);
        if (debitAt < 0) return null;
        int creditAt = firstIndex(CREDIT, lower);
        if (creditAt >= 0 && creditAt < debitAt) return null;

        double amount = amount(lower);
        if (amount <= 0) return null;

        Transaction t = new Transaction();
        t.amount = amount;
        t.timestamp = timestamp;
        t.source = source;
        t.sender = sender;
        t.merchant = merchant(text);
        t.accountLast4 = group(ACCOUNT, text);
        if (t.accountLast4.length() > 4) t.accountLast4 = t.accountLast4.substring(t.accountLast4.length() - 4);
        t.refNo = group(REF, text);
        if (t.refNo.isEmpty()) t.refNo = group(REF_UPI_PATH, text);
        t.category = Categorizer.guess(t.merchant, "");
        // Same SMS must always map to the same id so re-scans never create duplicates.
        t.id = t.refNo.isEmpty()
                ? sha256(sender + "|" + text + "|" + timestamp)
                : sha256("ref|" + t.refNo + "|" + Math.round(amount * 100));
        return t;
    }

    private static double amount(String lower) {
        String raw = group(AMOUNT, lower);
        if (raw.isEmpty()) raw = group(AMOUNT_BARE, lower);
        if (raw.isEmpty()) return 0;
        try {
            return Double.parseDouble(raw.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String merchant(String text) {
        for (Pattern p : MERCHANT) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                String name = m.group(1).replaceAll("\\s+", " ").replaceAll("[\\s.\\-]+$", "").trim();
                String l = name.toLowerCase(Locale.ROOT);
                if (name.length() < 2 || l.equals("you") || l.equals("your") || l.startsWith("your ")
                        || l.startsWith("know") || l.startsWith("block")) continue;
                return name.length() > 40 ? name.substring(0, 40) : name;
            }
        }
        return "Unknown";
    }

    private static int firstIndex(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.start() : -1;
    }

    private static String group(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : "";
    }

    static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
