/*
 * Copyright (C) 2026 The Evolution X Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.evolution.updater.ui;

import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.evolution.updater.R;
import org.evolution.updater.misc.BuildInfoUtils;
import org.evolution.updater.misc.ChangelogFetcher;
import org.evolution.updater.misc.ChangelogParser;
import org.evolution.updater.misc.ChangelogSection;
import org.evolution.updater.misc.Utils;

import java.util.List;

/**
 * Tab Changelog: 2 the — thong tin ROM + noi dung changelog.
 */
public class ChangelogFragment extends Fragment {

    private static final String TAG = "ChangelogFragment";

    private View mLoadingView;
    private View mErrorView;
    private View mEmptyView;
    private View mContentView;
    private TextView mRomInfoView;
    private TextView mBodyView;
    private boolean mLoaded;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_changelog, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mLoadingView = view.findViewById(R.id.changelog_loading);
        mErrorView = view.findViewById(R.id.changelog_error);
        mEmptyView = view.findViewById(R.id.changelog_empty);
        mContentView = view.findViewById(R.id.changelog_content);
        mRomInfoView = view.findViewById(R.id.changelog_rom_info);
        mBodyView = view.findViewById(R.id.changelog_body);

        mRomInfoView.setText(BuildInfoUtils.getRomInfoLine(requireContext()));

        view.findViewById(R.id.changelog_retry).setOnClickListener(v -> loadChangelog(true));
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!mLoaded) {
            loadChangelog(false);
        }
    }

    private void loadChangelog(boolean force) {
        if (force) {
            mLoaded = false;
        }
        if (mLoaded) {
            return;
        }

        showState(State.LOADING);
        final String url = Utils.getChangelogURL(requireContext());
        Log.d(TAG, "Fetching changelog " + url);

        new Thread(() -> {
            List<ChangelogSection> sections = null;
            try {
                String text = ChangelogFetcher.fetch(url);
                sections = ChangelogParser.parse(text);
            } catch (Exception e) {
                Log.e(TAG, "Could not fetch changelog", e);
            }
            final List<ChangelogSection> result = sections;
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) {
                    return;
                }
                mLoaded = true;
                if (result == null) {
                    showState(State.ERROR);
                } else if (result.isEmpty()) {
                    showState(State.EMPTY);
                } else {
                    bindChangelogBody(result);
                    showState(State.CONTENT);
                }
            });
        }, "ChangelogFetch").start();
    }

    private void bindChangelogBody(List<ChangelogSection> sections) {
        SpannableStringBuilder builder = new SpannableStringBuilder();
        for (int i = 0; i < sections.size(); i++) {
            ChangelogSection section = sections.get(i);
            if (i > 0) {
                builder.append("\n\n");
            }
            if (section.title != null && !section.title.isEmpty()) {
                builder.append(section.title);
                if (section.date != null && !section.date.isEmpty()
                        && !section.title.contains(section.date)) {
                    builder.append("\n").append(section.date);
                }
                builder.append("\n");
            }
            for (String item : section.items) {
                builder.append("• ").append(item).append("\n");
            }
        }
        mBodyView.setText(builder.toString().trim());
        Linkify.addLinks(mBodyView, Linkify.WEB_URLS);
        mBodyView.setMovementMethod(LinkMovementMethod.getInstance());
    }

    private enum State {
        LOADING, CONTENT, ERROR, EMPTY
    }

    private void showState(State state) {
        mLoadingView.setVisibility(state == State.LOADING ? View.VISIBLE : View.GONE);
        mErrorView.setVisibility(state == State.ERROR ? View.VISIBLE : View.GONE);
        mEmptyView.setVisibility(state == State.EMPTY ? View.VISIBLE : View.GONE);
        mContentView.setVisibility(state == State.CONTENT ? View.VISIBLE : View.GONE);

        if (state == State.ERROR) {
            TextView errorText = mErrorView.findViewById(R.id.changelog_error_text);
            errorText.setText(R.string.snack_updates_check_failed);
        }
    }
}
