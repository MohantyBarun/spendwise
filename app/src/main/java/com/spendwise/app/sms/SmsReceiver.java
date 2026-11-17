package com.spendwise.app.sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.spendwise.app.core.SmsParser;

/** Sees every incoming SMS, but only bank debit alerts get past the parser; everything else is dropped here. */
public class SmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
        SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (parts == null || parts.length == 0 || parts[0] == null) return;

        StringBuilder body = new StringBuilder();
        for (SmsMessage p : parts) {
            if (p != null && p.getMessageBody() != null) body.append(p.getMessageBody());
        }
        String sender = parts[0].getOriginatingAddress();
        long ts = parts[0].getTimestampMillis();
        if (ts <= 0) ts = System.currentTimeMillis();

        if (SmsParser.parse(sender, body.toString(), ts) == null) return;

        // Hand off to WorkManager so the save survives the receiver's short lifetime.
        Data data = new Data.Builder()
                .putString(SmsIngestWorker.KEY_SENDER, sender)
                .putString(SmsIngestWorker.KEY_BODY, body.toString())
                .putLong(SmsIngestWorker.KEY_TS, ts)
                .build();
        WorkManager.getInstance(context)
                .enqueue(new OneTimeWorkRequest.Builder(SmsIngestWorker.class).setInputData(data).build());
    }
}
