package com.spendwise.app.ui;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.TextViewCompat;

import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.spendwise.app.App;
import com.spendwise.app.R;
import com.spendwise.app.core.Insights;
import com.spendwise.app.core.Money;
import com.spendwise.app.data.Repo;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/** Weekly summary with saving tips. Opens on the current week; "Earlier" walks back through history. */
public class ReportActivity extends AppCompatActivity {
    private long weekStart;
    private TextView tvWeek, tvTotal, tvCompare;
    private LinearLayout tips, categories, merchants;
    private View btnNext;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Repo.uid() == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_report);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        tvWeek = findViewById(R.id.tvWeek);
        tvTotal = findViewById(R.id.tvTotal);
        tvCompare = findViewById(R.id.tvCompare);
        tips = findViewById(R.id.tips);
        categories = findViewById(R.id.categories);
        merchants = findViewById(R.id.merchants);
        btnNext = findViewById(R.id.btnNext);

        weekStart = Insights.weekStart(System.currentTimeMillis());
        findViewById(R.id.btnPrev).setOnClickListener(v -> {
            weekStart = Insights.addDays(weekStart, -7);
            load();
        });
        btnNext.setOnClickListener(v -> {
            weekStart = Insights.addDays(weekStart, 7);
            load();
        });
        load();
    }

    private void load() {
        final long requested = weekStart;
        SimpleDateFormat f = new SimpleDateFormat("d MMM", Locale.getDefault());
        tvWeek.setText(f.format(new Date(weekStart)) + " – " + f.format(new Date(Insights.addDays(weekStart, 6))));
        btnNext.setEnabled(weekStart < Insights.weekStart(System.currentTimeMillis()));

        Repo.txns()
                .whereGreaterThanOrEqualTo("timestamp", Insights.historyStart(weekStart))
                .whereLessThan("timestamp", Insights.addDays(weekStart, 7))
                .get()
                .addOnSuccessListener(this, snap -> {
                    if (requested != weekStart) return; // user moved on to another week
                    show(Insights.build(Repo.list(snap), requested, App.budget(this), System.currentTimeMillis()));
                })
                .addOnFailureListener(this, e -> {
                    tvTotal.setText("");
                    tvCompare.setText("Could not load this week. Check your connection.");
                });
    }

    private void show(Insights.Report r) {
        tvTotal.setText(Money.fmt(r.total));
        StringBuilder compare = new StringBuilder(r.count + (r.count == 1 ? " payment" : " payments"));
        if (r.daysElapsed < 7) {
            compare.append(" · ").append(r.daysElapsed).append(r.daysElapsed == 1 ? " day in" : " days in");
            if (r.prevSamePoint > 0) compare.append(" · last week by now ").append(Money.fmt(r.prevSamePoint));
        } else if (r.prevTotal > 0) {
            compare.append(" · last week ").append(Money.fmt(r.prevTotal));
        }
        if (r.usualWeekly > 0) compare.append(" · usual ").append(Money.fmt(r.usualWeekly));
        tvCompare.setText(compare);

        tips.removeAllViews();
        for (String tip : r.tips) tips.addView(text("•  " + tip, false));

        categories.removeAllViews();
        for (Map.Entry<String, Double> e : r.byCategory.entrySet()) {
            int percent = r.total <= 0 ? 0 : (int) Math.round(e.getValue() / r.total * 100);
            categories.addView(text(e.getKey() + "  —  " + Money.fmt(e.getValue()) + "  (" + percent + "%)", false));
            LinearProgressIndicator bar = new LinearProgressIndicator(this);
            bar.setMax(100);
            bar.setProgress(percent);
            categories.addView(bar);
        }
        if (r.byCategory.isEmpty()) categories.addView(text("Nothing this week.", true));

        merchants.removeAllViews();
        for (Map.Entry<String, Double> e : r.topMerchants.entrySet()) {
            merchants.addView(text(e.getKey() + "  —  " + Money.fmt(e.getValue()), false));
        }
        if (r.topMerchants.isEmpty()) merchants.addView(text("Nothing this week.", true));
    }

    private TextView text(String s, boolean muted) {
        TextView tv = new TextView(this);
        tv.setText(s);
        TextViewCompat.setTextAppearance(tv, muted
                ? com.google.android.material.R.style.TextAppearance_Material3_BodySmall
                : com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        int pad = (int) (8 * getResources().getDisplayMetrics().density);
        tv.setPadding(0, pad, 0, pad / 2);
        return tv;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
