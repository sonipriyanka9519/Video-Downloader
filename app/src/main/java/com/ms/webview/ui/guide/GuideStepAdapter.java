package com.ms.webview.ui.guide;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

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
        h.image.setImageResource(steps[position].image);
    }

    @Override
    public int getItemCount() {
        return steps.length;
    }

    static class StepHolder extends RecyclerView.ViewHolder {
        final ImageView image;

        StepHolder(@NonNull View itemView) {
            super(itemView);
            image = (ImageView) itemView;
        }
    }
}
