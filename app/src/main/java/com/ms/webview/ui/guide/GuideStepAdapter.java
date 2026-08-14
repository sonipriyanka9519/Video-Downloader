package com.ms.webview.ui.guide;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ms.webview.R;

/** The steps of a guide, swiped through one at a time. */
public class GuideStepAdapter extends RecyclerView.Adapter<GuideStepAdapter.StepHolder> {

    private final GuideStep[] steps;

    public GuideStepAdapter(GuideStep[] steps) {
        this.steps = steps;
    }

    @NonNull
    @Override
    public StepHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new StepHolder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_guide_step, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull StepHolder h, int position) {
        GuideStep step = steps[position];
        // Counted from one, because the viewer is being told to do a first thing, not a zeroth.
        h.number.setText(String.valueOf(position + 1));
        // setText(int) reads the resource as styled text, so the <b> around the names of things
        // to tap survives — that emphasis is what makes the line scannable rather than read.
        h.title.setText(step.title);
        h.image.setImageResource(step.image);
    }

    @Override
    public int getItemCount() {
        return steps.length;
    }

    static class StepHolder extends RecyclerView.ViewHolder {

        final TextView number;
        final TextView title;
        final ImageView image;

        StepHolder(@NonNull View itemView) {
            super(itemView);
            number = itemView.findViewById(R.id.stepNumber);
            title = itemView.findViewById(R.id.stepTitle);
            image = itemView.findViewById(R.id.stepImage);
        }
    }
}
