package com.ms.webview.ui.guide;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ms.webview.R;

/** The walkthrough's pages: a number, a line, and a picture. */
public class HowStepAdapter extends RecyclerView.Adapter<HowStepAdapter.StepHolder> {

    private final HowStep[] steps;

    public HowStepAdapter(HowStep[] steps) {
        this.steps = steps;
    }

    @NonNull
    @Override
    public StepHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new StepHolder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_how_step, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull StepHolder holder, int position) {
        HowStep step = steps[position];
        // Counted from one, because the viewer is being told to do a first thing, not a zeroth.
        holder.number.setText(String.valueOf(position + 1));
        holder.lead.setText(step.lead);
        holder.accent.setText(step.accent);
        holder.image.setImageResource(step.image);
    }

    @Override
    public int getItemCount() {
        return steps.length;
    }

    static class StepHolder extends RecyclerView.ViewHolder {

        final TextView number;
        final TextView lead;
        final TextView accent;
        final ImageView image;

        StepHolder(@NonNull View itemView) {
            super(itemView);
            number = itemView.findViewById(R.id.howNumber);
            lead = itemView.findViewById(R.id.howLead);
            accent = itemView.findViewById(R.id.howAccent);
            image = itemView.findViewById(R.id.howImage);
        }
    }
}
