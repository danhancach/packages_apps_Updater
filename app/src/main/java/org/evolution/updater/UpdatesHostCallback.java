/*
 * Copyright (C) 2026 The Evolution X Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.evolution.updater;

import android.view.View;

import org.evolution.updater.controller.UpdaterService;

/**
 * Giao tiep giua UpdatesActivity va cac tab Fragment (M3 navigation).
 */
public interface UpdatesHostCallback {

    UpdaterService getUpdaterService();

    UpdatesListAdapter getUpdatesAdapter();

    /** Adapter OTA local (LOCAL_ID); null tren TV. */
    UpdatesListAdapter getLocalUpdatesAdapter();

    void downloadUpdatesList(boolean manualRefresh);

    void onImportLocalUpdate();

    void updateHeaderInfo();

    View getSnackbarAnchor();
}
