package com.spendwise.app;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.spendwise.app.notify.DailyReminderWorker;
import com.spendwise.app.notify.Notifier;
import com.spendwise.app.notify.WeeklyReportWorker;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

public class App extends Application {
    /** Daily "add descriptions" reminder, 24h clock. */
    private static final int REMINDER_HOUR = 21;
    /** Weekly report: Sunday evening. */
    private static final int REPORT_DAY = Calendar.SUNDAY;
    private static final int REPORT_HOUR = 20;

    private static final String PREFS = "prefs";
    private static final String KEY_BUDGET = "monthly_budget";
    private static final String KEY_LOCK = "app_lock";

    @Override
    public void onCreate() {
        super.onCreate();
        Notifier.createChannel(this);

        WorkManager wm = WorkManager.getInstance(this);
        wm.enqueueUniquePeriodicWork("daily_reminder", ExistingPeriodicWorkPolicy.KEEP,
                new PeriodicWorkRequest.Builder(DailyReminderWorker.class, 24, TimeUnit.HOURS)
                        .setInitialDelay(delayUntil(-1, REMINDER_HOUR), TimeUnit.MILLISECONDS).build());
        wm.enqueueUniquePeriodicWork("weekly_report", ExistingPeriodicWorkPolicy.KEEP,
                new PeriodicWorkRequest.Builder(WeeklyReportWorker.class, 7, TimeUnit.DAYS)
                        .setInitialDelay(delayUntil(REPORT_DAY, REPORT_HOUR), TimeUnit.MILLISECONDS).build());
    }

    /** Milliseconds until the next occurrence of hour:00 (on dayOfWeek, or any day if -1). */
    private static long delayUntil(int dayOfWeek, int hour) {
        Calendar now = Calendar.getInstance();
        Calendar next = (Calendar) now.clone();
        next.set(Calendar.HOUR_OF_DAY, hour);
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        while (!next.after(now) || (dayOfWeek != -1 && next.get(Calendar.DAY_OF_WEEK) != dayOfWeek)) {
            next.add(Calendar.DAY_OF_MONTH, 1);
        }
        return next.getTimeInMillis() - now.getTimeInMillis();
    }

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    public static double budget(Context ctx) {
        return prefs(ctx).getFloat(KEY_BUDGET, 0f);
    }

    public static void setBudget(Context ctx, double value) {
        prefs(ctx).edit().putFloat(KEY_BUDGET, (float) value).apply();
    }

    public static boolean lockEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_LOCK, true);
    }

    public static void setLockEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_LOCK, on).apply();
    }
}
