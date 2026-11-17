package com.spendwise.app.notify;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.spendwise.app.core.Insights;
import com.spendwise.app.core.Money;
import com.spendwise.app.data.Repo;
import com.spendwise.app.model.Transaction;
import com.spendwise.app.ui.MainActivity;

/** Evening nudge: "N payments need a description". Silent when there is nothing to do. */
public class DailyReminderWorker extends Worker {
    public DailyReminderWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        if (Repo.uid() == null) return Result.success();
        try {
            int pending = 0;
            double amount = 0;
            for (Transaction t : Repo.loadSince(Insights.addDays(System.currentTimeMillis(), -3))) {
                if (t.needsDescription()) {
                    pending++;
                    amount += t.amount;
                }
            }
            if (pending > 0) {
                Intent open = new Intent(getApplicationContext(), MainActivity.class)
                        .putExtra(MainActivity.EXTRA_PENDING_ONLY, true);
                Notifier.show(getApplicationContext(), Notifier.ID_DAILY,
                        pending + (pending == 1 ? " payment needs" : " payments need") + " a description",
                        Money.fmt(amount) + " spent recently is still unlabelled. Tap to add a quick note while you remember.",
                        open);
            }
        } catch (Exception ignored) {
            // Try again tomorrow.
        }
        return Result.success();
    }
}
