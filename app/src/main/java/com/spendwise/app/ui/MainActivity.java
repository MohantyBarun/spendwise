package com.spendwise.app.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.spendwise.app.App;
import com.spendwise.app.BuildConfig;
import com.spendwise.app.R;
import com.spendwise.app.core.Insights;
import com.spendwise.app.core.Money;
import com.spendwise.app.core.SampleData;
import com.spendwise.app.data.Repo;
import com.spendwise.app.model.Transaction;
import com.spendwise.app.sms.InboxScanner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    public static final String EXTRA_PENDING_ONLY = "pending_only";

    private static final int AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
    /** Unlocked once per app process; a fresh launch asks again. */
    private static boolean unlocked = false;
    private static boolean caughtUp = false;

    private final List<Transaction> all = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private TransactionAdapter adapter;
    private ListenerRegistration listener;
    private View root;
    private TextView tvWeekTotal, tvPending, tvEmpty;
    private MaterialSwitch swPending;
    private View btnSample;

    private final ActivityResultLauncher<String[]> permissions = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> catchUp());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Repo.uid() == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_main);
        root = findViewById(R.id.root);
        tvWeekTotal = findViewById(R.id.tvWeekTotal);
        tvPending = findViewById(R.id.tvPending);
        tvEmpty = findViewById(R.id.tvEmpty);
        swPending = findViewById(R.id.swPending);

        adapter = new TransactionAdapter(t ->
                startActivity(new Intent(this, EditTransactionActivity.class)
                        .putExtra(EditTransactionActivity.EXTRA_ID, t.id)));
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        list.setAdapter(adapter);

        swPending.setChecked(getIntent().getBooleanExtra(EXTRA_PENDING_ONLY, false));
        swPending.setOnCheckedChangeListener((b, checked) -> render());
        btnSample = findViewById(R.id.btnSample);
        btnSample.setOnClickListener(v -> loadSamples());
        findViewById(R.id.fabAdd).setOnClickListener(v ->
                startActivity(new Intent(this, EditTransactionActivity.class)));

        unlockThen(this::askPermissions);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (swPending != null && intent.getBooleanExtra(EXTRA_PENDING_ONLY, false)) swPending.setChecked(true);
    }

    // ---- App lock ----

    private void unlockThen(Runnable next) {
        boolean canLock = BiometricManager.from(this).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS;
        if (unlocked || !App.lockEnabled(this) || !canLock) {
            unlocked = true;
            next.run();
            return;
        }
        root.setVisibility(View.INVISIBLE);
        new BiometricPrompt(this, ContextCompat.getMainExecutor(this), new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                unlocked = true;
                root.setVisibility(View.VISIBLE);
                next.run();
            }

            @Override
            public void onAuthenticationError(int code, @NonNull CharSequence message) {
                finish();
            }
        }).authenticate(new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock SpendWise")
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build());
    }

    // ---- Permissions and SMS catch-up ----

    private boolean has(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private String[] missingPermissions() {
        List<String> missing = new ArrayList<>();
        if (BuildConfig.SMS_CAPTURE) {
            if (!has(Manifest.permission.RECEIVE_SMS)) missing.add(Manifest.permission.RECEIVE_SMS);
            if (!has(Manifest.permission.READ_SMS)) missing.add(Manifest.permission.READ_SMS);
        }
        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return missing.toArray(new String[0]);
    }

    private void askPermissions() {
        String[] missing = missingPermissions();
        if (missing.length == 0) {
            catchUp();
            return;
        }
        if (App.prefs(this).getBoolean("asked_permissions", false)) return; // asked once; menu > Scan asks again
        App.prefs(this).edit().putBoolean("asked_permissions", true).apply();
        if (!BuildConfig.SMS_CAPTURE) {
            permissions.launch(missing); // demo edition: only notifications
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("Track payments automatically")
                .setMessage("SpendWise reads bank SMS alerts on this phone to log your UPI and card payments. "
                        + "Only the amount, merchant and reference number are saved to your private account. "
                        + "Message text is never uploaded and other SMS are ignored.\n\n"
                        + "Notifications are used for the evening reminder and weekly report.")
                .setPositiveButton("Continue", (d, w) -> permissions.launch(missing))
                .setNegativeButton("Not now", null)
                .show();
    }

    /** Quietly picks up bank SMS from the last few days that the live receiver may have missed. */
    private void catchUp() {
        if (!BuildConfig.SMS_CAPTURE || caughtUp || !has(Manifest.permission.READ_SMS)) return;
        caughtUp = true;
        scan(3, false);
    }

    private void scan(int days, boolean announce) {
        if (!has(Manifest.permission.READ_SMS)) {
            permissions.launch(missingPermissions());
            return;
        }
        if (announce) Toast.makeText(this, "Scanning inbox…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            String message;
            try {
                int added = InboxScanner.scan(getApplicationContext(), days);
                message = added == 0 ? "No new payments found" : added + " payments added";
            } catch (Exception e) {
                message = "Scan failed. Check your connection and try again.";
            }
            String shown = message;
            if (announce) runOnUiThread(() -> Toast.makeText(this, shown, Toast.LENGTH_LONG).show());
        });
    }

    // ---- List ----

    @Override
    protected void onStart() {
        super.onStart();
        if (Repo.uid() == null) return;
        listener = Repo.txns().orderBy("timestamp", Query.Direction.DESCENDING).limit(500)
                .addSnapshotListener((snap, error) -> {
                    if (snap == null) return;
                    all.clear();
                    all.addAll(Repo.list(snap));
                    render();
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (listener != null) listener.remove();
        listener = null;
    }

    private void render() {
        long weekStart = Insights.weekStart(System.currentTimeMillis());
        double weekTotal = 0;
        int pending = 0;
        List<Transaction> shown = new ArrayList<>();
        for (Transaction t : all) {
            if (t.timestamp >= weekStart) weekTotal += t.amount;
            if (t.needsDescription()) pending++;
            if (!swPending.isChecked() || t.needsDescription()) shown.add(t);
        }
        tvWeekTotal.setText(Money.fmt(weekTotal));
        tvPending.setText(pending == 0 ? "Every payment has a description."
                : pending + (pending == 1 ? " payment needs" : " payments need") + " a description");
        adapter.submit(shown);
        tvEmpty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        btnSample.setVisibility(all.isEmpty() && !BuildConfig.SMS_CAPTURE ? View.VISIBLE : View.GONE);
        if (swPending.isChecked()) {
            tvEmpty.setText("Nothing left to describe.");
        } else if (BuildConfig.SMS_CAPTURE) {
            tvEmpty.setText("No expenses yet. Payments appear here when your bank's SMS arrives, or add one yourself.");
        } else {
            tvEmpty.setText("No expenses yet.\n\nLong-press a bank SMS in your messages app, tap Share, and pick "
                    + "SpendWise. Or add one yourself, or try the app with sample data.");
        }
    }

    private void loadSamples() {
        Repo.saveAll(SampleData.generate(System.currentTimeMillis(), System.nanoTime()));
        Toast.makeText(this, "Sample data added. Open the weekly report to see the tips.", Toast.LENGTH_LONG).show();
    }

    private void clearSamples() {
        Repo.deleteSamples().addOnCompleteListener(this, t -> Toast.makeText(this,
                t.isSuccessful() ? "Sample data removed" : "Could not remove sample data. Try again online.",
                Toast.LENGTH_SHORT).show());
    }

    // ---- Menu ----

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.action_lock).setChecked(App.lockEnabled(this));
        menu.findItem(R.id.action_scan).setVisible(BuildConfig.SMS_CAPTURE);
        menu.findItem(R.id.action_sample).setVisible(!BuildConfig.SMS_CAPTURE);
        menu.findItem(R.id.action_clear_sample).setVisible(!BuildConfig.SMS_CAPTURE);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_report) {
            startActivity(new Intent(this, ReportActivity.class));
        } else if (id == R.id.action_scan) {
            scan(30, true);
        } else if (id == R.id.action_sample) {
            loadSamples();
        } else if (id == R.id.action_clear_sample) {
            clearSamples();
        } else if (id == R.id.action_budget) {
            editBudget();
        } else if (id == R.id.action_lock) {
            App.setLockEnabled(this, !App.lockEnabled(this));
        } else if (id == R.id.action_signout) {
            FirebaseAuth.getInstance().signOut();
            unlocked = false;
            caughtUp = false;
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        } else {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }

    private void editBudget() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("e.g. 20000");
        double current = App.budget(this);
        if (current > 0) input.setText(String.valueOf(Math.round(current)));
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Monthly spending budget (₹)")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String s = input.getText().toString().trim();
                    App.setBudget(this, s.isEmpty() ? 0 : Double.parseDouble(s));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }
}
