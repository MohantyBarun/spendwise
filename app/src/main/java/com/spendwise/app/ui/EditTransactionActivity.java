package com.spendwise.app.ui;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.spendwise.app.R;
import com.spendwise.app.core.Categorizer;
import com.spendwise.app.data.Repo;
import com.spendwise.app.model.Transaction;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Add a manual expense, or describe / re-categorise one captured from SMS. */
public class EditTransactionActivity extends AppCompatActivity {
    public static final String EXTRA_ID = "id";

    private Transaction txn = new Transaction();
    private boolean existing;
    private EditText etNote, etAmount, etMerchant;
    private Spinner spCategory;
    private CheckBox cbRemember;
    private Button btnDate;
    private TextView tvInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Repo.uid() == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_edit);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        etNote = findViewById(R.id.etNote);
        etAmount = findViewById(R.id.etAmount);
        etMerchant = findViewById(R.id.etMerchant);
        spCategory = findViewById(R.id.spCategory);
        cbRemember = findViewById(R.id.cbRemember);
        btnDate = findViewById(R.id.btnDate);
        tvInfo = findViewById(R.id.tvInfo);

        spCategory.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, Categorizer.CATEGORIES));
        btnDate.setOnClickListener(v -> pickDate());
        findViewById(R.id.btnSave).setOnClickListener(v -> save());

        String id = getIntent().getStringExtra(EXTRA_ID);
        existing = id != null;
        if (existing) {
            setTitle("Describe payment");
            Repo.txns().document(id).get().addOnSuccessListener(this, snap -> {
                if (!snap.exists()) {
                    finish();
                    return;
                }
                txn = Repo.from(snap);
                bind();
            }).addOnFailureListener(this, e -> finish());
        } else {
            setTitle("Add expense");
            txn.timestamp = System.currentTimeMillis();
            bind();
        }
    }

    private void bind() {
        etNote.setText(txn.note);
        etMerchant.setText(txn.merchant);
        if (txn.amount > 0) {
            etAmount.setText(txn.amount == Math.rint(txn.amount)
                    ? String.valueOf((long) txn.amount) : String.valueOf(txn.amount));
        }
        spCategory.setSelection(Math.max(0, Categorizer.CATEGORIES.indexOf(txn.category)));
        btnDate.setText(new SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(new Date(txn.timestamp)));

        StringBuilder info = new StringBuilder();
        if (Transaction.SOURCE_SAMPLE.equals(txn.source)) {
            info.append("Sample data");
        } else if (Transaction.SOURCE_SMS.equals(txn.source) || Transaction.SOURCE_SHARED.equals(txn.source)) {
            info.append(Transaction.SOURCE_SMS.equals(txn.source) ? "Captured from bank SMS" : "Added from a shared SMS");
            if (Transaction.SOURCE_SMS.equals(txn.source) && !txn.sender.isEmpty()) info.append(" (").append(txn.sender).append(')');
            if (!txn.accountLast4.isEmpty()) info.append(" · a/c ending ").append(txn.accountLast4);
            if (!txn.refNo.isEmpty()) info.append(" · ref ").append(txn.refNo);
        }
        tvInfo.setText(info);
        tvInfo.setVisibility(info.length() == 0 ? View.GONE : View.VISIBLE);
        if (existing && txn.needsDescription()) etNote.requestFocus();
        invalidateOptionsMenu();
    }

    private void pickDate() {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(txn.timestamp);
        DatePickerDialog dialog = new DatePickerDialog(this, (view, y, m, d) -> {
            c.set(y, m, d);
            txn.timestamp = c.getTimeInMillis();
            btnDate.setText(new SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(c.getTime()));
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        dialog.getDatePicker().setMaxDate(System.currentTimeMillis());
        dialog.show();
    }

    private void save() {
        double amount;
        try {
            amount = Double.parseDouble(etAmount.getText().toString().trim());
        } catch (NumberFormatException e) {
            amount = 0;
        }
        if (amount <= 0) {
            etAmount.setError("Enter the amount");
            return;
        }
        txn.amount = amount;
        txn.note = etNote.getText().toString().trim();
        txn.merchant = etMerchant.getText().toString().trim();
        String chosen = (String) spCategory.getSelectedItem();
        // If the user described it but left the category alone, let the description decide.
        txn.category = Categorizer.UNCATEGORISED.equals(chosen) ? Categorizer.guess(txn.merchant, txn.note) : chosen;

        // Not waiting for the server: the local cache updates instantly and syncs in the background.
        Repo.save(txn);
        if (cbRemember.isChecked() && !Categorizer.UNCATEGORISED.equals(chosen)) {
            Repo.rememberMerchant(txn.merchant, chosen);
        }
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (existing) getMenuInflater().inflate(R.menu.edit, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        if (item.getItemId() == R.id.action_delete && txn.id != null) {
            new MaterialAlertDialogBuilder(this)
                    .setMessage("Delete this expense?")
                    .setPositiveButton("Delete", (d, w) -> {
                        Repo.delete(txn.id);
                        finish();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
