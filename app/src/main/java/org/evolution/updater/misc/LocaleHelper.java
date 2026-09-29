/*
 * Copyright (C) 2026 The Evolution X Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.evolution.updater.misc;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;
import android.text.TextUtils;

import java.util.Locale;

/**
 * Locale app: Pref + updateConfiguration Application/Activity + Locale.setDefault.
 * Soft-refresh UI trong Activity (applyLocaleStringsOnly) — khong setApplicationLocales.
 * tag rong = theo he thong (SYSTEM).
 */
public final class LocaleHelper {

    private LocaleHelper() {
    }

    /**
     * wrap Context cho attachBaseContext. Tag rong → khong wrap (system).
     */
    public static Context wrap(Context context, String tag) {
        if (context == null) {
            return null;
        }
        if (TextUtils.isEmpty(tag)) {
            return context;
        }
        if (!"vi".equals(tag) && !"en".equals(tag)) {
            return context;
        }
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocales(new LocaleList(Locale.forLanguageTag(tag)));
        return context.createConfigurationContext(config);
    }

    /** Locale tu tag; rong / null → locale he thong. */
    public static Locale resolveLocale(String tag) {
        if (TextUtils.isEmpty(tag)) {
            LocaleList system = Resources.getSystem().getConfiguration().getLocales();
            return system.isEmpty() ? Locale.getDefault() : system.get(0);
        }
        return Locale.forLanguageTag(tag);
    }

    /**
     * Ap dung locale len Resources cua Context (Application hoac Activity).
     * Tag rong → locales he thong.
     */
    @SuppressWarnings("deprecation")
    public static void applyToResources(Context context, String tag) {
        if (context == null) {
            return;
        }
        Resources resources = context.getResources();
        Configuration config = new Configuration(resources.getConfiguration());
        if (TextUtils.isEmpty(tag)) {
            config.setLocales(Resources.getSystem().getConfiguration().getLocales());
        } else {
            config.setLocales(new LocaleList(Locale.forLanguageTag(tag)));
        }
        resources.updateConfiguration(config, resources.getDisplayMetrics());
    }

    /**
     * LSPosed-style: Locale.setDefault + updateConfiguration Application va Activity.
     */
    public static void applyAppAndActivity(Application app, Context activity, String tag) {
        String safe = tag != null ? tag : "";
        Locale.setDefault(resolveLocale(safe));
        if (app != null) {
            applyToResources(app, safe);
        }
        if (activity != null) {
            applyToResources(activity, safe);
        }
    }

    /** Chi Application (UpdaterApp.onCreate). */
    public static void applyToApplication(Application app, String tag) {
        if (app == null) {
            return;
        }
        String safe = tag != null ? tag : "";
        Locale.setDefault(resolveLocale(safe));
        applyToResources(app, safe);
    }
}
