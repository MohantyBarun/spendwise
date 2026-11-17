package com.spendwise.app.sms;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.spendwise.app.core.SmsParser;
import com.spendwise.app.data.Repo;
import com.spendwise.app.model.Transaction;

public class SmsIngestWorker extends Worker {
    static final String KEY_SENDER = "sender";
    static final String KEY_BODY = "body";
    static final String KEY_TS = "ts";

    public SmsIngestWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        if (Repo.uid() == null) return Result.success(); // signed out: nothing is recorded
        Transaction t = SmsParser.parse(
                getInputData().getString(KEY_SENDER),
                getInputData().getString(KEY_BODY),
                getInputData().getLong(KEY_TS, System.currentTimeMillis()));
        if (t == null) return Result.success();
        try {
            Repo.insertIfAbsent(t);
            return Result.success();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.retry();
        } catch (RuntimeException e) {
            return Result.retry();
        }
    }
}
