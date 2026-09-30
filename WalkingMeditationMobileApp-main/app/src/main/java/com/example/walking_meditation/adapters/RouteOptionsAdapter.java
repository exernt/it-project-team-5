package com.example.walking_meditation.adapters;

import com.example.walking_meditation.R;
import com.example.walking_meditation.models.RouteOption;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.Locale;

public class RouteOptionsAdapter extends RecyclerView.Adapter<RouteOptionsAdapter.VH> {

    public interface OnRouteClickListener {
        void onRouteClicked(RouteOption option);
    }

    private final List<RouteOption> items;
    private final OnRouteClickListener listener;
    private int selectedPosition = RecyclerView.NO_POSITION;

    public RouteOptionsAdapter(List<RouteOption> items, OnRouteClickListener listener) {
        this.items = items;
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.row_route_option, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        RouteOption opt = items.get(position);

        holder.tvTitle.setText(opt.title);
        holder.itemView.setSelected(position == selectedPosition);

        // Duration line
        String durationText = formatDuration(opt.durationSeconds);
        holder.tvDuration.setText("Time: " + durationText);

        // Extra line
        if (opt.addedSeconds > 0) {
            holder.tvExtra.setText("Added vs baseline: +" + formatDuration(opt.addedSeconds));
        } else {
            holder.tvExtra.setText("Baseline route (no detour)");
        }

        holder.itemView.setOnClickListener(v -> {
            int previous = selectedPosition;
            selectedPosition = holder.getBindingAdapterPosition();
            if (previous != RecyclerView.NO_POSITION) notifyItemChanged(previous);
            if (selectedPosition != RecyclerView.NO_POSITION) notifyItemChanged(selectedPosition);
            listener.onRouteClicked(opt);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public void clearSelection() {
        int previous = selectedPosition;
        selectedPosition = RecyclerView.NO_POSITION;
        if (previous != RecyclerView.NO_POSITION) notifyItemChanged(previous);
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvTitle;
        TextView tvDuration;
        TextView tvExtra;

        VH(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tv_title);
            tvDuration = itemView.findViewById(R.id.tv_duration);
            tvExtra = itemView.findViewById(R.id.tv_extra);
        }
    }

    private static String formatDuration(int seconds) {
        int s = Math.max(0, seconds);
        int min = s / 60;
        int rem = s % 60;
        if (min >= 60) {
            int h = min / 60;
            int m = min % 60;
            return String.format(Locale.US, "%dh %dm", h, m);
        }
        return String.format(Locale.US, "%dm %02ds", min, rem);
    }
}
