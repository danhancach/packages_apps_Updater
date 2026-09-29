/*
 * Copyright (C) 2026 The Evolution X Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.evolution.updater.ui;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemProperties;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.color.MaterialColors;

import org.evolution.updater.R;
import org.evolution.updater.UpdatesHostCallback;
import org.evolution.updater.UpdatesListAdapter;

/**
 * Tab Trang chu: trang thai thiet bi + OTA dam may / bo nho.
 * CollapsingToolbar + banner Evolution nam trong fragment (pattern ReSukiSU).
 */
public class HomeFragment extends Fragment {

    private static final String TAG = "HomeFragment";

    private UpdatesHostCallback mHost;
    private RecyclerView mRecyclerView;
    private RecyclerView mLocalRecyclerView;
    private View mCloudEmpty;
    private View mLocalEmpty;
    private SwipeRefreshLayout mSwipeRefresh;
    private CollapsingToolbarLayout mCollapsingToolbar;
    private View mBrandHeader;

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
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mCollapsingToolbar = view.findViewById(R.id.home_collapsing_toolbar);
        mBrandHeader = view.findViewById(R.id.home_brand_header);
        AppBarLayout appBar = view.findViewById(R.id.home_app_bar);
        // Expanded: banner; collapsed: tieu de tab Home
        if (appBar != null && mCollapsingToolbar != null) {
            appBar.addOnOffsetChangedListener((bar, verticalOffset) -> {
                int range = bar.getTotalScrollRange();
                float expand = range == 0 ? 1f
                        : 1f - (Math.abs(verticalOffset) / (float) range);
                boolean collapsed = range > 0 && Math.abs(verticalOffset) >= range * 0.6f;
                if (collapsed) {
                    mCollapsingToolbar.setTitle(getString(R.string.nav_tab_home));
                    if (mBrandHeader != null) {
                        mBrandHeader.setAlpha(0f);
                    }
                } else {
                    mCollapsingToolbar.setTitle("");
                    if (mBrandHeader != null) {
                        mBrandHeader.setAlpha(Math.max(0f, Math.min(1f, expand * 1.4f)));
                    }
                }
            });
        }

        mSwipeRefresh = view.findViewById(R.id.home_swipe_refresh);
        // colorPrimary o AppCompat (Soong material.R.attr khong co colorPrimary)
        int primary = MaterialColors.getColor(requireContext(),
                androidx.appcompat.R.attr.colorPrimary, TAG);
        int surface = MaterialColors.getColor(requireContext(),
                com.google.android.material.R.attr.colorSurface, TAG);
        mSwipeRefresh.setColorSchemeColors(primary);
        mSwipeRefresh.setProgressBackgroundColorSchemeColor(surface);
        mSwipeRefresh.setOnRefreshListener(() -> mHost.downloadUpdatesList(true));

        NestedScrollView scroll = view.findViewById(R.id.home_scroll);
        // Edge fade / overscroll da set trong XML; mau glow theo theme he thong
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            scroll.setVerticalFadingEdgeEnabled(true);
        }

        mCloudEmpty = view.findViewById(R.id.home_cloud_empty);
        mLocalEmpty = view.findViewById(R.id.home_local_empty);

        mRecyclerView = view.findViewById(R.id.recycler_view);
        setupRecycler(mRecyclerView, mHost.getUpdatesAdapter());

        mLocalRecyclerView = view.findViewById(R.id.local_recycler_view);
        UpdatesListAdapter localAdapter = mHost.getLocalUpdatesAdapter();
        if (localAdapter != null) {
            setupRecycler(mLocalRecyclerView, localAdapter);
        }

        view.findViewById(R.id.home_import_button).setOnClickListener(
                v -> mHost.onImportLocalUpdate());

        mHost.updateHeaderInfo();
    }

    private void setupRecycler(RecyclerView recycler, UpdatesListAdapter adapter) {
        recycler.setAdapter(adapter);
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        RecyclerView.ItemAnimator animator = recycler.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }
    }

    /** Hien/an danh sach OTA online trong the dam may; empty khi khong co. */
    public void setUpdatesVisible(boolean hasOnlineUpdates) {
        if (mRecyclerView == null) {
            return;
        }
        mRecyclerView.setVisibility(hasOnlineUpdates ? View.VISIBLE : View.GONE);
        if (mCloudEmpty != null) {
            mCloudEmpty.setVisibility(hasOnlineUpdates ? View.GONE : View.VISIBLE);
        }
    }

    /** Toggle empty vs list trong the local; hang Them ban cap nhat luon hien. */
    public void setLocalUpdateVisible(boolean hasLocalUpdate) {
        if (mLocalRecyclerView == null) {
            return;
        }
        mLocalRecyclerView.setVisibility(hasLocalUpdate ? View.VISIBLE : View.GONE);
        if (mLocalEmpty != null) {
            mLocalEmpty.setVisibility(hasLocalUpdate ? View.GONE : View.VISIBLE);
        }
    }

    public void setRefreshing(boolean refreshing) {
        if (mSwipeRefresh != null) {
            mSwipeRefresh.setRefreshing(refreshing);
        }
    }

    /**
     * Cap nhat the trang thai: ROM, Android, Kernel, ngay ban dung, last check.
     */
    public void bindHeader(String romVersion, String androidVersion, String buildDate,
            String lastCheck) {
        View root = getView();
        if (root == null) {
            return;
        }
        TextView versionView = root.findViewById(R.id.header_build_version);
        TextView androidView = root.findViewById(R.id.header_android_version);
        TextView kernelView = root.findViewById(R.id.header_kernel_version);
        TextView dateView = root.findViewById(R.id.header_build_date);
        TextView lastCheckView = root.findViewById(R.id.header_last_check);
        versionView.setText(romVersion);
        androidView.setText(androidVersion);
        if (kernelView != null) {
            // Prefix "Kernel" (about_kernel_subtitle) truoc so phien ban
            kernelView.setText(getString(R.string.home_kernel_format, getKernelVersionLabel()));
        }
        dateView.setText(buildDate);
        lastCheckView.setText(lastCheck);
    }

    /** Kernel: os.version → ro.kernel.version → Build.UNKNOWN */
    private static String getKernelVersionLabel() {
        String kernel = System.getProperty("os.version");
        if (kernel == null || kernel.isEmpty()) {
            kernel = SystemProperties.get("ro.kernel.version", "");
        }
        if (kernel == null || kernel.isEmpty()) {
            kernel = Build.UNKNOWN;
        }
        return kernel;
    }

    public void bindUpdateStatus(boolean hasOnlineUpdates, int updateCount) {
        View root = getView();
        if (root == null) {
            return;
        }
        TextView statusTitle = root.findViewById(R.id.home_status_title);
        if (hasOnlineUpdates && updateCount > 0) {
            statusTitle.setText(getResources().getQuantityString(
                    R.plurals.home_status_updates_available, updateCount, updateCount));
            statusTitle.setTextColor(MaterialColors.getColor(requireContext(),
                    androidx.appcompat.R.attr.colorPrimary, TAG));
        } else {
            statusTitle.setText(R.string.home_status_up_to_date);
            statusTitle.setTextColor(MaterialColors.getColor(requireContext(),
                    com.google.android.material.R.attr.colorOnSurface, TAG));
        }
    }

}
