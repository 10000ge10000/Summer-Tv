package com.github.tvbox.osc.commercial;

import android.app.Activity;
import android.view.ViewGroup;

public interface AdProvider {
    void showPlacement(Activity activity, ViewGroup parent, AdPlacement placement);
}
