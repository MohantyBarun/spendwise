package com.spendwise.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import com.spendwise.app.core.SmsParser;
import com.spendwise.app.data.Repo;
import com.spendwise.app.model.Transaction;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Receives text shared from an SMS app ("Share > Add to SpendWise"). This is how payments get in
 * without SMS permission: the user picks the exact message, so nothing else is ever read.
 * Opens the new expense straight away so a description can be added while it is fresh.
 */
public class ShareActivity extends Activity {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        CharSequence shared = in == null ? null : in.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (!Intent.ACTION_SEND.equals(in == null ? null : in.getAction()) || shared == null) {
            finish();
            return;
        }
        if (Repo.uid() == null) {
            Toast.makeText(this, "Sign in to SpendWise first, then share the message again.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, LoginActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }

        Transaction t = SmsParser.parseShared(shared.toString(), System.currentTimeMillis());
        if (t == null) {
            Toast.makeText(this, "No payment found in that message. Add it by hand instead.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, EditTransactionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }

        android.content.Context app = getApplicationContext();
        IO.execute(() -> {
            boolean added;
            try {
                added = Repo.insertIfAbsent(t);
            } catch (Exception e) {
                added = false;
            }
            boolean isNew = added;
            runOnUiThread(() -> {
                Toast.makeText(app, isNew ? "Payment added. Add a quick description." : "Already recorded.", Toast.LENGTH_SHORT).show();
                startActivity(new Intent(app, EditTransactionActivity.class)
                        .putExtra(EditTransactionActivity.EXTRA_ID, t.id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                finish();
            });
        });
    }
}
