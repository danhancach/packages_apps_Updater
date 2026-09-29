/*
 * Copyright (C) 2026 The Evolution X Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.evolution.updater.ui;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.evolution.updater.R;

/**
 * Tab Thong tin: Updater app + ho tro (Telegram/GitHub/Maintainer).
 * Phien ban phan mem (Android/ROM/Kernel) nam o tab Home.
 */
public class AboutFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_about, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Summary = versionName (title nam o layout)
        TextView appVersion = view.findViewById(R.id.about_app_version);
        appVersion.setText(getAppVersionLabel());

        View forumRow = view.findViewById(R.id.support_forum);
        View sourceRow = view.findViewById(R.id.support_source);
        forumRow.setOnClickListener(v -> openUrl(getString(R.string.support_forum_url)));
        sourceRow.setOnClickListener(v -> openUrl(getString(R.string.support_source_url)));

        // Maintainer: ten chinh + subtitle (khong prefix "Maintainer: ")
        TextView maintainerName = view.findViewById(R.id.about_maintainer_name);
        maintainerName.setText(R.string.default_maintainer_name);
    }

    /**
     * No-op: thong tin Android/ROM/Kernel da chuyen sang Home.
     * UpdatesActivity van goi — giu signature de khong crash.
     */
    public void bindBuildInfo(String androidVersion, String romVersion, String buildDate) {
        // Intentionally empty
    }

    private String getAppVersionLabel() {
        try {
            PackageInfo info = requireContext().getPackageManager().getPackageInfo(
                    requireContext().getPackageName(), 0);
            return info.versionName != null ? info.versionName : String.valueOf(info.versionCode);
        } catch (PackageManager.NameNotFoundException e) {
            return "—";
        }
    }

    private void openUrl(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        startActivity(intent);
    }
}
