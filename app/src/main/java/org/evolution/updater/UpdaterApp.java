/*
 * Copyright (C) 2026 The Evolution X Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.evolution.updater;

import android.app.Application;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.evolution.updater.misc.Constants;
import org.evolution.updater.misc.LocaleHelper;

/**
 * Locale: Pref + Application resources (pattern LSPosed App.java).
 *
 * Dynamic Color: KHONG dung DynamicColors.applyToActivitiesIfAvailable (Soong
 * material-x + SettingsLib → crash Theme.AppCompat). Monet theo ReSukiSU:
 * applyStyle ThemeOverlay.Material3.DynamicColors.DayNight (system_accent*)
 * trong UpdatesActivity truoc super.onCreate.
 */
public class UpdaterApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String tag = prefs.getString(Constants.PREF_APP_LOCALE, "");
        LocaleHelper.applyToApplication(this, tag != null ? tag : "");
    }
}
