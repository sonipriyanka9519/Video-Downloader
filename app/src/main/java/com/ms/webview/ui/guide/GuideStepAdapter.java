package com.ms.webview.ui.guide;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ms.webview.R;

/** The steps of a guide, swiped through one at a time. */
public class GuideStepAdapter extends RecyclerView.Adapter<GuideStepAdapter.StepHolder> {

    private final GuideStep[] steps;
    /** The site being explained. Only the first step names it, but the adapter is what fills it. */
    private final CharSequence siteName;
    /** And its address, for the one drawing that shows a copied link. */
    private final CharSequence siteHost;

    public GuideStepAdapter(CharSequence siteName, CharSequence siteHost, GuideStep[] steps) {
        this.siteName = siteName;
        this.siteHost = siteHost;
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
        // getText and expandTemplate rather than getString and format, because both halves matter:
        // getText keeps the <b> around the names of things to tap, which is the emphasis that makes
        // the line scannable, and expandTemplate fills ^1 without flattening those spans the way
        // String.format would.
        //
        // What it fills in is the site's name. One set of three steps serves every guide, because
        // the method is the same on all of them and the first line is the only place the site is
        // named.
        Context context = h.title.getContext();
        h.title.setText(TextUtils.expandTemplate(
                context.getText(step.title), siteName));

        // Replaced rather than added to: a recycled page holding the previous drawing as well as
        // its own would stack two of them.
        h.art.removeAllViews();
        LayoutInflater.from(h.art.getContext()).inflate(step.art, h.art, true);

        // One drawing carries an address, and it has to be this site's: a fixed one would show
        // instagram.com inside the Facebook guide. Looked up rather than passed in, because only
        // one of the three drawings has anywhere to put it.
        TextView sample = h.art.findViewById(R.id.artLinkSample);
        if (sample != null) {
            sample.setText(sample.getContext().getString(R.string.guide_art_link_sample, siteHost));
        }
    }

    @Override
    public int getItemCount() {
        return steps.length;
    }

    static class StepHolder extends RecyclerView.ViewHolder {

        final TextView number;
        final TextView title;
        final ViewGroup art;

        StepHolder(@NonNull View itemView) {
            super(itemView);
            number = itemView.findViewById(R.id.stepNumber);
            title = itemView.findViewById(R.id.stepTitle);
            art = itemView.findViewById(R.id.stepArt);
        }
    }
}
