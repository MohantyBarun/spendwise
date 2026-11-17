package com.spendwise.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.Patterns;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.spendwise.app.BuildConfig;
import com.spendwise.app.R;

public class LoginActivity extends AppCompatActivity {
    private EditText etEmail, etPassword;
    private View progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        progress = findViewById(R.id.progress);

        findViewById(R.id.btnSignIn).setOnClickListener(v -> submit(false));
        findViewById(R.id.btnCreate).setOnClickListener(v -> submit(true));
        findViewById(R.id.btnForgot).setOnClickListener(v -> resetPassword());

        // Demo edition: let people try it in one tap. Firebase anonymous auth still gives each guest a
        // private uid, so the same security rules keep guests apart.
        View guest = findViewById(R.id.btnGuest);
        guest.setVisibility(BuildConfig.SMS_CAPTURE ? View.GONE : View.VISIBLE);
        guest.setOnClickListener(v -> {
            progress.setVisibility(View.VISIBLE);
            FirebaseAuth.getInstance().signInAnonymously().addOnCompleteListener(this, this::onSignedIn);
        });
    }

    private void onSignedIn(Task<AuthResult> done) {
        progress.setVisibility(View.GONE);
        if (done.isSuccessful()) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
        } else {
            String msg = done.getException() == null ? "Sign-in failed" : done.getException().getLocalizedMessage();
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        }
    }

    private String email() {
        String email = etEmail.getText().toString().trim();
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            etEmail.setError("Enter a valid email");
            return null;
        }
        return email;
    }

    private void submit(boolean create) {
        String email = email();
        if (email == null) return;
        String password = etPassword.getText().toString();
        if (password.length() < (create ? 10 : 1)) {
            etPassword.setError(create ? "Use at least 10 characters" : "Enter your password");
            return;
        }
        progress.setVisibility(View.VISIBLE);
        FirebaseAuth auth = FirebaseAuth.getInstance();
        Task<AuthResult> task = create
                ? auth.createUserWithEmailAndPassword(email, password)
                : auth.signInWithEmailAndPassword(email, password);
        task.addOnCompleteListener(this, this::onSignedIn);
    }

    private void resetPassword() {
        String email = email();
        if (email == null) return;
        FirebaseAuth.getInstance().sendPasswordResetEmail(email).addOnCompleteListener(this, done ->
                Toast.makeText(this, "If that account exists, a reset link has been emailed.", Toast.LENGTH_LONG).show());
    }
}
