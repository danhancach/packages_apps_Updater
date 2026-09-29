/*
 * Copyright (C) 2026 The Evolution X Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.evolution.updater.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemProperties;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.shape.ShapeAppearanceModel;

import org.evolution.updater.R;
import org.evolution.updater.UpdatesActivity;
import org.evolution.updater.UpdatesCheckReceiver;
import org.evolution.updater.UpdatesHostCallback;
import org.evolution.updater.controller.UpdaterService;
import org.evolution.updater.misc.Constants;
import org.evolution.updater.misc.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tab Cai dat: preference Material3, luu khi roi tab (onPause).
 * Locale: hang jump + dialog; Pref + LocaleHelper + soft-refresh (khong recreate).
 */
public class SettingsFragment extends Fragment {

    /** Dialog ngon ngu: Theo he thong / Tieng Viet / English */
    private static final int LOCALE_SYSTEM = 0;
    private static final int LOCALE_VI = 1;
    private static final int LOCALE_EN = 2;

    private UpdatesHostCallback mHost;
    private View mLanguageRow;
    private TextView mLanguageSummary;
    private Spinner mIntervalSpinner;
    private MaterialSwitch mAutoDelete;
    private MaterialSwitch mMeteredNetworkWarning;
    private MaterialSwitch mAbPerfMode;
    private MaterialSwitch mUpdateRecovery;
    private MaterialCardView mAutoDeleteCard;
    private MaterialCardView mMeteredNetworkCard;
    private MaterialCardView mAbPerfModeCard;
    private MaterialCardView mUpdateRecoveryCard;
    private int mSelectedInterval = 0;
    private boolean mSpinnerReady;

    private boolean mPreferencesBound;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (!(context instanceof UpdatesHostCallback)) {
            throw new IllegalStateException("Activity phai implement UpdatesHostCallback");
        }
        mHost = (UpdatesHostCallback) context;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mLanguageRow = view.findViewById(R.id.settings_language_row);
        mLanguageSummary = view.findViewById(R.id.settings_language_summary);
        mIntervalSpinner = view.findViewById(R.id.auto_check_interval_spinner);
        mAutoDelete = view.findViewById(R.id.preferences_auto_delete_updates);
        mMeteredNetworkWarning = view.findViewById(R.id.preferences_metered_network_warning);
        mAbPerfMode = view.findViewById(R.id.preferences_ab_perf_mode);
        mUpdateRecovery = view.findViewById(R.id.preferences_update_recovery);
        mAutoDeleteCard = view.findViewById(R.id.preferences_auto_delete_card);
        mMeteredNetworkCard = view.findViewById(R.id.preferences_metered_network_card);
        mAbPerfModeCard = view.findViewById(R.id.preferences_ab_perf_mode_card);
        mUpdateRecoveryCard = view.findViewById(R.id.preferences_update_recovery_card);

        setupLanguagePreference();
        setupIntervalSpinner();

        if (!Utils.isABDevice()) {
            // An ca the (Settings Expressive: khong de card rong)
            mAbPerfModeCard.setVisibility(View.GONE);
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        mAutoDelete.setChecked(prefs.getBoolean(Constants.PREF_AUTO_DELETE_UPDATES, false));
        mMeteredNetworkWarning.setChecked(prefs.getBoolean(Constants.PREF_METERED_NETWORK_WARNING,
                prefs.getBoolean(Constants.PREF_MOBILE_DATA_WARNING, true)));
        mAbPerfMode.setChecked(prefs.getBoolean(Constants.PREF_AB_PERF_MODE, false));

        if (getResources().getBoolean(R.bool.config_hideRecoveryUpdate)) {
            mUpdateRecoveryCard.setVisibility(View.GONE);
        } else if (Utils.isRecoveryUpdateExecPresent()) {
            mUpdateRecovery.setChecked(
                    SystemProperties.getBoolean(Constants.UPDATE_RECOVERY_PROPERTY, false));
        } else {
            mUpdateRecovery.setChecked(true);
            mUpdateRecovery.setOnTouchListener(new View.OnTouchListener() {
                private Toast forcedUpdateToast = null;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (forcedUpdateToast != null) {
                        forcedUpdateToast.cancel();
                    }
                    forcedUpdateToast = Toast.makeText(requireContext().getApplicationContext(),
                            getString(R.string.toast_forced_update_recovery), Toast.LENGTH_SHORT);
                    forcedUpdateToast.show();
                    return true;
                }
            });
        }

        // Bo goc Top/Center/Bottom theo the dang hien (SettingsLib expressive)
        applyBehaviorGroupCorners();
        mPreferencesBound = true;
    }

    /**
     * Gan shape Top / Center / Bottom / Single cho cac the Behavior dang VISIBLE —
     * giong SettingsPreferenceGroupAdapter (20dp mep ngoai, 4dp mep noi).
     */
    private void applyBehaviorGroupCorners() {
        MaterialCardView[] all = {
                mAutoDeleteCard,
                mMeteredNetworkCard,
                mAbPerfModeCard,
                mUpdateRecoveryCard
        };
        List<MaterialCardView> visible = new ArrayList<>();
        for (MaterialCardView card : all) {
            if (card != null && card.getVisibility() == View.VISIBLE) {
                visible.add(card);
            }
        }
        final int n = visible.size();
        for (int i = 0; i < n; i++) {
            int shapeRes;
            if (n == 1) {
                shapeRes = R.style.ShapeAppearance_Updater_Preference_Single;
            } else if (i == 0) {
                shapeRes = R.style.ShapeAppearance_Updater_Preference_Top;
            } else if (i == n - 1) {
                shapeRes = R.style.ShapeAppearance_Updater_Preference_Bottom;
            } else {
                shapeRes = R.style.ShapeAppearance_Updater_Preference_Center;
            }
            visible.get(i).setShapeAppearanceModel(
                    ShapeAppearanceModel.builder(requireContext(), shapeRes, 0).build());
        }
    }

    /** Hang jump ngon ngu (ReSukiSU): summary = ngon ngu hien tai; click mo dialog. */
    private void setupLanguagePreference() {
        updateLanguageSummary();
        mLanguageRow.setOnClickListener(v -> showLanguageSelectionDialog());
    }

    private void updateLanguageSummary() {
        final String[] entries = getResources().getStringArray(R.array.settings_language_entries);
        int index = localeTagToIndex(resolveCurrentLocaleTag());
        if (index < 0 || index >= entries.length) {
            index = LOCALE_SYSTEM;
        }
        mLanguageSummary.setText(entries[index]);
    }

    /** Dialog chon ngon ngu — giong LanguageSelectionDialog (list single-choice). */
    private void showLanguageSelectionDialog() {
        final String[] entries = getResources().getStringArray(R.array.settings_language_entries);
        final int checked = localeTagToIndex(resolveCurrentLocaleTag());
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_language)
                .setSingleChoiceItems(entries, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which != checked) {
                        applyAppLocale(which);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Doc locale hien tai tu Pref ("" = he thong). */
    private String resolveCurrentLocaleTag() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        String stored = prefs.getString(Constants.PREF_APP_LOCALE, "");
        return stored != null ? stored : "";
    }

    private static int localeTagToIndex(String tag) {
        if (tag == null || tag.isEmpty()) {
            return LOCALE_SYSTEM;
        }
        String lang = tag.toLowerCase(Locale.ROOT);
        if (lang.startsWith("vi")) {
            return LOCALE_VI;
        }
        if (lang.startsWith("en")) {
            return LOCALE_EN;
        }
        return LOCALE_SYSTEM;
    }

    private static String indexToLocaleTag(int index) {
        switch (index) {
            case LOCALE_VI:
                return "vi";
            case LOCALE_EN:
                return "en";
            case LOCALE_SYSTEM:
            default:
                return "";
        }
    }

    /**
     * Pref → soft-refresh noi dung (cung Activity).
     * applyLocaleStringsOnly da goi LocaleHelper.applyAppAndActivity.
     * Khong setApplicationLocales / khong finish / recreate / fade.
     */
    private void applyAppLocale(int index) {
        String tag = indexToLocaleTag(index);
        PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit()
                .putString(Constants.PREF_APP_LOCALE, tag)
                .commit();

        if (!(requireActivity() instanceof UpdatesActivity)) {
            return;
        }
        ((UpdatesActivity) requireActivity()).applyLocaleStringsOnly(tag);
    }

    /** Gan ArrayAdapter interval — pattern BatteryMonitor Spinner. */
    private void setupIntervalSpinner() {
        final String[] entries = getResources().getStringArray(
                R.array.menu_auto_updates_check_interval_entries);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(),
                R.layout.updater_spinner_item,
                entries);
        adapter.setDropDownViewResource(R.layout.updater_spinner_dropdown_item);
        mIntervalSpinner.setAdapter(adapter);

        mSelectedInterval = Utils.getUpdateCheckSetting(requireContext());
        if (mSelectedInterval < 0 || mSelectedInterval >= entries.length) {
            mSelectedInterval = Constants.AUTO_UPDATES_CHECK_INTERVAL_NEVER;
        }
        mSpinnerReady = false;
        mIntervalSpinner.setSelection(mSelectedInterval, false);
        mIntervalSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View itemView, int position,
                    long id) {
                // Phong thu: listener co the goi ngay khi setSelection
                if (!mSpinnerReady) {
                    mSpinnerReady = true;
                    mSelectedInterval = position;
                    return;
                }
                mSelectedInterval = position;
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        mSpinnerReady = true;
    }

    @Override
    public void onPause() {
        super.onPause();
        // Chi luu khi tab Settings dang visible — tranh crash update_engine khi hide/show
        if (isVisible() && !isHidden()) {
            persistPreferences();
        }
    }

    private void persistPreferences() {
        if (!mPreferencesBound || mIntervalSpinner == null) {
            return;
        }

        Context context = requireContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit()
                .putInt(Constants.PREF_AUTO_UPDATES_CHECK_INTERVAL, mSelectedInterval)
                .putBoolean(Constants.PREF_AUTO_DELETE_UPDATES, mAutoDelete.isChecked())
                .putBoolean(Constants.PREF_METERED_NETWORK_WARNING,
                        mMeteredNetworkWarning.isChecked())
                .putBoolean(Constants.PREF_AB_PERF_MODE, mAbPerfMode.isChecked())
                .apply();

        if (Utils.isUpdateCheckEnabled(context)) {
            UpdatesCheckReceiver.scheduleRepeatingUpdatesCheck(context);
        } else {
            UpdatesCheckReceiver.cancelRepeatingUpdatesCheck(context);
            UpdatesCheckReceiver.cancelUpdatesCheck(context);
        }

        if (Utils.isABDevice()) {
            UpdaterService service = mHost.getUpdaterService();
            if (service != null) {
                try {
                    service.getUpdaterController().setPerformanceMode(mAbPerfMode.isChecked());
                } catch (RuntimeException e) {
                    // update_engine co the chua san sang — khong crash UI
                }
            }
        }
        if (Utils.isRecoveryUpdateExecPresent()) {
            SystemProperties.set(Constants.UPDATE_RECOVERY_PROPERTY,
                    String.valueOf(mUpdateRecovery.isChecked()));
        }
    }
}
