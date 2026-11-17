package com.spendwise.app.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.spendwise.app.R;
import com.spendwise.app.core.Money;
import com.spendwise.app.model.Transaction;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

class TransactionAdapter extends RecyclerView.Adapter<TransactionAdapter.Holder> {
    interface OnClick {
        void onClick(Transaction t);
    }

    private final List<Transaction> items = new ArrayList<>();
    private final OnClick onClick;
    private final SimpleDateFormat date = new SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault());

    TransactionAdapter(OnClick onClick) {
        this.onClick = onClick;
    }

    void submit(List<Transaction> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_transaction, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Transaction t = items.get(position);
        h.merchant.setText(t.merchant.isEmpty() ? "Unknown" : t.merchant);
        h.amount.setText(Money.exact(t.amount));
        h.meta.setText(t.category + " · " + date.format(new Date(t.timestamp)));
        if (t.needsDescription()) {
            h.note.setText("Tap to add a description");
            h.note.setTextColor(MaterialColors.getColor(h.note, androidx.appcompat.R.attr.colorPrimary));
        } else {
            h.note.setText(t.note);
            h.note.setTextColor(MaterialColors.getColor(h.note, com.google.android.material.R.attr.colorOnSurface));
        }
        h.itemView.setOnClickListener(v -> onClick.onClick(t));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView merchant, note, meta, amount;

        Holder(View v) {
            super(v);
            merchant = v.findViewById(R.id.tvMerchant);
            note = v.findViewById(R.id.tvNote);
            meta = v.findViewById(R.id.tvMeta);
            amount = v.findViewById(R.id.tvAmount);
        }
    }
}
