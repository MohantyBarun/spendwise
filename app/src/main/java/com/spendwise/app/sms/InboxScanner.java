package com.spendwise.app.sms;

import android.content.Context;
import android.database.Cursor;
import android.provider.Telephony;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.WriteBatch;
import com.spendwise.app.core.Insights;
import com.spendwise.app.core.SmsParser;
import com.spendwise.app.data.Repo;
import com.spendwise.app.model.Transaction;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Reads bank SMS already in the inbox. Used to import history on first run and to catch anything the
 * live receiver missed (some phones stop background receivers to save battery).
 */
public final class InboxScanner {
    private InboxScanner() {}

    /** Blocking; requires READ_SMS. @return number of new expenses added */
    public static int scan(Context ctx, int days) throws Exception {
        long since = Insights.addDays(System.currentTimeMillis(), -days);

        // Existing ids first, so descriptions the user already wrote are never overwritten.
        Set<String> known = new HashSet<>();
        for (Transaction t : Repo.loadSince(Insights.addDays(since, -2))) known.add(t.id);
        Map<String, String> rules = Repo.loadMerchantRules();

        int added = 0;
        WriteBatch batch = FirebaseFirestore.getInstance().batch();
        int inBatch = 0;
        try (Cursor c = ctx.getContentResolver().query(
                Telephony.Sms.Inbox.CONTENT_URI,
                new String[]{Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT},
                Telephony.Sms.DATE + " > ?", new String[]{String.valueOf(since)}, null)) {
            if (c == null) return 0;
            while (c.moveToNext()) {
                long sent = c.getLong(3);
                Transaction t = SmsParser.parse(c.getString(0), c.getString(1), sent > 0 ? sent : c.getLong(2));
                if (t == null || !known.add(t.id)) continue;
                Repo.applyRule(t, rules);
                batch.set(Repo.txns().document(t.id), Repo.toMap(t));
                added++;
                if (++inBatch == 400) {
                    commit(batch);
                    batch = FirebaseFirestore.getInstance().batch();
                    inBatch = 0;
                }
            }
        }
        if (inBatch > 0) commit(batch);
        return added;
    }

    private static void commit(WriteBatch batch) throws Exception {
        try {
            Tasks.await(batch.commit(), 20, TimeUnit.SECONDS);
        } catch (TimeoutException offline) {
            // Queued locally; syncs when back online.
        }
    }
}
