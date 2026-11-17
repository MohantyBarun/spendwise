package com.spendwise.app.notify;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.spendwise.app.App;
import com.spendwise.app.core.Insights;
import com.spendwise.app.core.Money;
import com.spendwise.app.data.Repo;
import com.spendwise.app.ui.ReportActivity;

import java.util.Map;

/** Sunday-evening summary notification; the full report and tips open on tap. */
public class WeeklyReportWorker extends Worker {
    public WeeklyReportWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        if (Repo.uid() == null) return Result.success();
        try {
            long now = System.currentTimeMillis();
            long weekStart = Insights.weekStart(now);
            Insights.Report r = Insights.build(
                    Repo.loadSince(Insights.historyStart(weekStart)), weekStart, App.budget(getApplicationContext()), now);

            StringBuilder text = new StringBuilder();
            if (r.count == 0) {
                text.append("No expenses recorded this week.");
            } else {
                text.append(Money.fmt(r.total)).append(" across ").append(r.count).append(" payments.");
                if (!r.byCategory.isEmpty()) {
                    Map.Entry<String, Double> top = r.byCategory.entrySet().iterator().next();
                    text.append(" Biggest: ").append(top.getKey()).append(' ').append(Money.fmt(top.getValue())).append('.');
                }
                text.append(" Tap for ways to save.");
            }
            Notifier.show(getApplicationContext(), Notifier.ID_WEEKLY, "Your week in spending",
                    text.toString(), new Intent(getApplicationContext(), ReportActivity.class));
        } catch (Exception ignored) {
            // Report is still available in the app.
        }
        return Result.success();
    }
}
