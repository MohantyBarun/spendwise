package com.spendwise.app.data;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.Source;
import com.google.firebase.firestore.WriteBatch;
import com.spendwise.app.core.Categorizer;
import com.spendwise.app.model.Transaction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * All Firestore access. Data lives under users/{uid}/..., and firestore.rules only lets the
 * signed-in owner read or write that subtree. Firestore's offline cache means every call here
 * also works without a connection and syncs later.
 */
public final class Repo {
    private Repo() {}

    public static String uid() {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        return u == null ? null : u.getUid();
    }

    private static DocumentReference userDoc() {
        String uid = uid();
        if (uid == null) throw new IllegalStateException("Not signed in");
        return FirebaseFirestore.getInstance().collection("users").document(uid);
    }

    public static CollectionReference txns() {
        return userDoc().collection("transactions");
    }

    private static CollectionReference merchantRules() {
        return userDoc().collection("merchantRules");
    }

    public static Map<String, Object> toMap(Transaction t) {
        Map<String, Object> m = new HashMap<>();
        m.put("amount", t.amount);
        m.put("merchant", t.merchant == null ? "" : t.merchant);
        m.put("note", t.note == null ? "" : t.note);
        m.put("category", t.category == null ? Categorizer.UNCATEGORISED : t.category);
        m.put("timestamp", t.timestamp);
        m.put("source", t.source);
        m.put("accountLast4", t.accountLast4);
        m.put("refNo", t.refNo);
        m.put("sender", t.sender);
        return m;
    }

    public static Transaction from(DocumentSnapshot s) {
        Transaction t = new Transaction();
        t.id = s.getId();
        Double amount = s.getDouble("amount");
        t.amount = amount == null ? 0 : amount;
        t.merchant = str(s, "merchant", "");
        t.note = str(s, "note", "");
        t.category = str(s, "category", Categorizer.UNCATEGORISED);
        Long ts = s.getLong("timestamp");
        t.timestamp = ts == null ? 0 : ts;
        t.source = str(s, "source", Transaction.SOURCE_MANUAL);
        t.accountLast4 = str(s, "accountLast4", "");
        t.refNo = str(s, "refNo", "");
        t.sender = str(s, "sender", "");
        return t;
    }

    private static String str(DocumentSnapshot s, String field, String fallback) {
        String v = s.getString(field);
        return v == null ? fallback : v;
    }

    public static List<Transaction> list(QuerySnapshot snap) {
        List<Transaction> out = new ArrayList<>();
        for (DocumentSnapshot d : snap.getDocuments()) out.add(from(d));
        return out;
    }

    /** Fire-and-forget: the write is applied to the local cache immediately and synced when online. */
    public static Task<Void> save(Transaction t) {
        if (t.id == null) t.id = txns().document().getId();
        return txns().document(t.id).set(toMap(t));
    }

    public static Task<Void> delete(String id) {
        return txns().document(id).delete();
    }

    /** Writes up to 500 transactions in one atomic batch. */
    public static Task<Void> saveAll(List<Transaction> list) {
        WriteBatch batch = FirebaseFirestore.getInstance().batch();
        for (Transaction t : list) batch.set(txns().document(t.id), toMap(t));
        return batch.commit();
    }

    /** Removes everything that "Load sample data" added; real expenses are untouched. */
    public static Task<Void> deleteSamples() {
        return txns().whereEqualTo("source", Transaction.SOURCE_SAMPLE).get().continueWithTask(task -> {
            WriteBatch batch = FirebaseFirestore.getInstance().batch();
            for (DocumentSnapshot d : task.getResult().getDocuments()) batch.delete(d.getReference());
            return batch.commit();
        });
    }

    // ---- Merchant -> category memory ----

    static String merchantKey(String merchant) {
        if (merchant == null) return null;
        String k = merchant.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (k.isEmpty() || k.equals("unknown")) return null;
        return k.length() > 100 ? k.substring(0, 100) : k;
    }

    /** Future payments to this merchant will be filed under the same category automatically. */
    public static void rememberMerchant(String merchant, String category) {
        String key = merchantKey(merchant);
        if (key == null || Categorizer.UNCATEGORISED.equals(category)) return;
        Map<String, Object> m = new HashMap<>();
        m.put("category", category);
        merchantRules().document(key).set(m);
    }

    // ---- Blocking helpers: call from background threads / Workers only ----

    public static List<Transaction> loadSince(long from) throws Exception {
        return list(Tasks.await(txns().whereGreaterThanOrEqualTo("timestamp", from).get(), 20, TimeUnit.SECONDS));
    }

    public static Map<String, String> loadMerchantRules() {
        Map<String, String> out = new HashMap<>();
        try {
            for (DocumentSnapshot d : Tasks.await(merchantRules().get(), 15, TimeUnit.SECONDS).getDocuments()) {
                String c = d.getString("category");
                if (c != null) out.put(d.getId(), c);
            }
        } catch (Exception ignored) {
            // No rules available right now: fall back to keyword guesses.
        }
        return out;
    }

    public static void applyRule(Transaction t, Map<String, String> rules) {
        String key = merchantKey(t.merchant);
        if (key != null && rules.containsKey(key)) t.category = rules.get(key);
    }

    /**
     * Stores an SMS-detected payment unless it is already known, so user edits are never overwritten.
     * @return true if it was new
     */
    public static boolean insertIfAbsent(Transaction t) throws InterruptedException {
        DocumentReference ref = txns().document(t.id);
        try {
            if (Tasks.await(ref.get(Source.CACHE), 5, TimeUnit.SECONDS).exists()) return false;
        } catch (ExecutionException | TimeoutException notCached) {
            // Not in the local cache => treat as new.
        }
        String key = merchantKey(t.merchant);
        if (key != null) {
            try {
                String c = Tasks.await(merchantRules().document(key).get(Source.CACHE), 5, TimeUnit.SECONDS)
                        .getString("category");
                if (c != null) t.category = c;
            } catch (ExecutionException | TimeoutException noRule) {
                // Keep the keyword guess.
            }
        }
        try {
            Tasks.await(ref.set(toMap(t)), 10, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException offline) {
            // Offline: the write is already queued in the local cache and will sync later.
        }
        return true;
    }
}
