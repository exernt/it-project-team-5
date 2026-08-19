package com.example.walking_meditation.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.walking_meditation.R;
import com.example.walking_meditation.models.NarrationItem;

import java.util.List;

public class NarrationAdapter extends RecyclerView.Adapter<NarrationAdapter.ViewHolder> {

    public interface OnNarrationClickListener {
        void onNarrationClicked(NarrationItem item, int position);
    }

    private final List<NarrationItem> items;
    private final OnNarrationClickListener listener;

    public NarrationAdapter(List<NarrationItem> items, OnNarrationClickListener listener) {
        this.items = items;
        this.listener = listener;
        setHasStableIds(true);
    }

    @Override
    public long getItemId(int position) {
        // stable id helps UI updates look smoother
        return (items.get(position).id + "_" + position).hashCode();
    }

    @NonNull
    @Override
    public NarrationAdapter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.row_narration_item, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull NarrationAdapter.ViewHolder holder, int position) {
        NarrationItem item = items.get(position);

        holder.tvText.setText(item.text);

        switch (item.status) {
            case READY:
                holder.progressBar.setVisibility(View.GONE);
                holder.tvStatus.setText("Ready to download");
                break;

            case DOWNLOADING:
                holder.progressBar.setVisibility(View.VISIBLE);
                holder.progressBar.setProgress(item.progress);
                holder.tvStatus.setText("Downloading… " + item.progress + "%");
                break;

            case DOWNLOADED:
                holder.progressBar.setVisibility(View.GONE);
                holder.tvStatus.setText("Downloaded ✓");
                break;

            case ERROR:
                holder.progressBar.setVisibility(View.GONE);
                holder.tvStatus.setText("Error – tap to retry");
                break;
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onNarrationClicked(item, position);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public final TextView tvText;
        public final TextView tvStatus;
        public final ProgressBar progressBar;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvText = itemView.findViewById(R.id.tvText);
            tvStatus = itemView.findViewById(R.id.tvStatus);
            progressBar = itemView.findViewById(R.id.progressBar);
        }
    }
}