/*
 * Copyright (C) 2017-2025 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.evolution.updater;

import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.text.method.LinkMovementMethod;
import android.text.format.Formatter;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.appcompat.widget.PopupMenu;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;

import org.evolution.updater.controller.UpdaterController;
import org.evolution.updater.controller.UpdaterService;
import org.evolution.updater.misc.Constants;
import org.evolution.updater.misc.StringGenerator;
import org.evolution.updater.misc.Utils;
import org.evolution.updater.model.Update;
import org.evolution.updater.model.UpdateInfo;
import org.evolution.updater.model.UpdateStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class UpdatesListAdapter extends RecyclerView.Adapter<UpdatesListAdapter.ViewHolder> {

    private static final String TAG = "UpdateListAdapter";

    private static final int BATTERY_PLUGGED_ANY = BatteryManager.BATTERY_PLUGGED_AC
            | BatteryManager.BATTERY_PLUGGED_USB
            | BatteryManager.BATTERY_PLUGGED_WIRELESS;

    private List<String> mDownloadIds;
    private String mSelectedDownload;
    private UpdaterController mUpdaterController;
    private final UpdatesListActivity mActivity;
    private final int mItemLayoutRes;

    private AlertDialog infoDialog;

    private enum Action {
        DOWNLOAD,
        PAUSE,
        RESUME,
        INSTALL,
        INFO,
        DELETE,
        CANCEL_INSTALLATION,
        REBOOT,
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        private final ImageButton mExpand;

        private final TextView mBuildDate;
        private final TextView mBuildUpdateType;
        private final TextView mBuildVersion;
        private final TextView mBuildSize;

        private final LinearLayout mProgress;
        private final ProgressBar mProgressBar;
        private final TextView mProgressText;
        private final TextView mPercentage;

        public ViewHolder(final View view) {
            super(view);
            mExpand = view.findViewById(R.id.update_expand);

            mBuildDate = view.findViewById(R.id.build_date);
            mBuildUpdateType = view.findViewById(R.id.build_update_type);
            mBuildVersion = view.findViewById(R.id.build_version);
            mBuildSize = view.findViewById(R.id.build_size);

            mProgress = view.findViewById(R.id.progress);
            mProgressBar = view.findViewById(R.id.progress_bar);
            mProgressText = view.findViewById(R.id.progress_text);
            mPercentage = view.findViewById(R.id.progress_percent);
        }
    }

    public UpdatesListAdapter(UpdatesListActivity activity) {
        this(activity, R.layout.update_item_view);
    }

    public UpdatesListAdapter(UpdatesListActivity activity, int itemLayoutRes) {
        mActivity = activity;
        mItemLayoutRes = itemLayoutRes;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(ViewGroup viewGroup, int i) {
        View view = LayoutInflater.from(viewGroup.getContext())
                .inflate(mItemLayoutRes, viewGroup, false);
        return new ViewHolder(view);
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull ViewHolder holder) {
        super.onViewDetachedFromWindow(holder);

        if (infoDialog != null) {
            infoDialog.dismiss();
        }
    }

    public void setUpdaterController(UpdaterController updaterController) {
        mUpdaterController = updaterController;
        notifyDataSetChanged();
    }

    private void handleActiveStatus(ViewHolder viewHolder, UpdateInfo update) {
        boolean canDelete = false;
        Action primaryAction;
        boolean primaryEnabled = true;

        final String downloadId = update.getDownloadId();
        if (mUpdaterController.isDownloading(downloadId)) {
            canDelete = true;
            String downloaded = Formatter.formatShortFileSize(mActivity,
                    update.getFile().length());
            String total = Formatter.formatShortFileSize(mActivity, update.getFileSize());
            String percentage = NumberFormat.getPercentInstance().format(
                    update.getProgress() / 100.f);
            viewHolder.mPercentage.setText(percentage);
            long eta = update.getEta();
            if (eta > 0) {
                CharSequence etaString = StringGenerator.formatETA(mActivity, eta * 1000);
                viewHolder.mProgressText.setText(mActivity.getString(
                        R.string.list_download_progress_eta_newer, downloaded, total, etaString));
            } else {
                viewHolder.mProgressText.setText(mActivity.getString(
                        R.string.list_download_progress_newer, downloaded, total));
            }
            primaryAction = Action.PAUSE;
            viewHolder.mProgressBar.setIndeterminate(update.getStatus() == UpdateStatus.STARTING);
            viewHolder.mProgressBar.setProgress(update.getProgress());
        } else if (mUpdaterController.isInstallingUpdate(downloadId)) {
            primaryAction = Action.CANCEL_INSTALLATION;
            boolean notAB = !mUpdaterController.isInstallingABUpdate();
            viewHolder.mProgressText.setText(notAB ? R.string.dialog_prepare_zip_message :
                    update.getFinalizing() ?
                            R.string.finalizing_package :
                            R.string.preparing_ota_first_boot);
            String percentage = NumberFormat.getPercentInstance().format(
                    update.getInstallProgress() / 100.f);
            viewHolder.mPercentage.setText(percentage);
            viewHolder.mProgressBar.setIndeterminate(false);
            viewHolder.mProgressBar.setProgress(update.getInstallProgress());
        } else if (mUpdaterController.isVerifyingUpdate(downloadId)) {
            primaryAction = Action.INSTALL;
            primaryEnabled = false;
            viewHolder.mProgressText.setText(R.string.list_verifying_update);
            viewHolder.mProgressBar.setIndeterminate(true);
        } else {
            canDelete = true;
            primaryAction = Action.RESUME;
            primaryEnabled = !isBusy();
            String downloaded = Formatter.formatShortFileSize(mActivity,
                    update.getFile().length());
            String total = Formatter.formatShortFileSize(mActivity, update.getFileSize());
            String percentage = NumberFormat.getPercentInstance().format(
                    update.getProgress() / 100.f);
            viewHolder.mPercentage.setText(percentage);
            viewHolder.mProgressText.setText(mActivity.getString(
                    R.string.list_download_progress_newer, downloaded, total));
            viewHolder.mProgressBar.setIndeterminate(false);
            viewHolder.mProgressBar.setProgress(update.getProgress());
        }

        bindExpandMenu(viewHolder, update, primaryAction, primaryEnabled, canDelete);
        viewHolder.mProgress.setVisibility(View.VISIBLE);
        viewHolder.mProgressText.setVisibility(View.VISIBLE);
        viewHolder.mBuildSize.setVisibility(View.INVISIBLE);
    }

    private void handleNotActiveStatus(ViewHolder viewHolder, UpdateInfo update) {
        final String downloadId = update.getDownloadId();
        Action primaryAction;
        boolean canDelete;
        boolean primaryEnabled = !isBusy();

        if (Update.LOCAL_ID.equals(downloadId)) {
            // Local OTA khong tai qua URL — chi cai dat hoac xoa
            canDelete = true;
            if (update.getPersistentStatus() == UpdateStatus.Persistent.VERIFIED) {
                primaryAction = Utils.canInstall(update) ? Action.INSTALL : Action.DELETE;
            } else {
                primaryAction = Action.DELETE;
            }
        } else if (update.getPersistentStatus() == UpdateStatus.Persistent.VERIFIED) {
            canDelete = true;
            primaryAction = Utils.canInstall(update) ? Action.INSTALL : Action.DELETE;
        } else if (!Utils.canInstall(update)) {
            canDelete = false;
            primaryAction = Action.INFO;
        } else {
            canDelete = false;
            primaryAction = Action.DOWNLOAD;
        }
        String fileSize = Formatter.formatShortFileSize(mActivity, update.getFileSize());
        viewHolder.mBuildSize.setText(fileSize);

        bindExpandMenu(viewHolder, update, primaryAction, primaryEnabled, canDelete);
        viewHolder.mProgress.setVisibility(View.INVISIBLE);
        viewHolder.mProgressText.setVisibility(View.INVISIBLE);
        viewHolder.mBuildSize.setVisibility(View.VISIBLE);
    }

    // UI cho trang thai cho reboot (local + online)
    private void handleWaitingForReboot(ViewHolder viewHolder, UpdateInfo update) {
        String fileSize = Formatter.formatShortFileSize(mActivity, update.getFileSize());
        viewHolder.mBuildSize.setText(fileSize);
        bindExpandMenu(viewHolder, update, Action.REBOOT, true, false);
        viewHolder.mProgress.setVisibility(View.INVISIBLE);
        viewHolder.mProgressText.setVisibility(View.INVISIBLE);
        viewHolder.mBuildSize.setVisibility(View.VISIBLE);
    }

    @Override
    public void onBindViewHolder(@NonNull final ViewHolder viewHolder, int i) {
        if (mDownloadIds == null) {
            viewHolder.mExpand.setEnabled(false);
            return;
        }

        final String downloadId = mDownloadIds.get(i);
        UpdateInfo update = mUpdaterController.getUpdate(downloadId);
        if (update == null) {
            // Ban cap nhat da bi xoa
            viewHolder.mExpand.setEnabled(false);
            return;
        }

        viewHolder.itemView.setSelected(downloadId.equals(mSelectedDownload));

        boolean activeLayout;
        switch (update.getPersistentStatus()) {
            case UpdateStatus.Persistent.UNKNOWN:
                activeLayout = update.getStatus() == UpdateStatus.STARTING;
                break;
            case UpdateStatus.Persistent.VERIFIED:
                activeLayout = update.getStatus() == UpdateStatus.INSTALLING;
                break;
            case UpdateStatus.Persistent.INCOMPLETE:
                activeLayout = true;
                break;
            default:
                throw new RuntimeException("Unknown update status");
        }

        String buildDate = StringGenerator.getDateLocalizedUTC(mActivity,
                DateFormat.LONG, update.getTimestamp());
        String buildVersion = mActivity.getString(R.string.list_build_version,
                update.getVersion());
        viewHolder.mBuildVersion.setText(buildVersion);
        viewHolder.mBuildVersion.setCompoundDrawables(null, null, null, null);
        // Phu de loai OTA (full / partial) — giu ngay build rieng
        viewHolder.mBuildUpdateType.setText(Utils.isIncrementalUpdate(update)
                ? R.string.update_type_partial
                : R.string.update_type_full);
        viewHolder.mBuildDate.setText(buildDate);

        // Reboot truoc active/LOCAL_ID — tranh ket FINALIZING sau khi engine xong
        if (mUpdaterController.isWaitingForReboot(downloadId)) {
            handleWaitingForReboot(viewHolder, update);
            return;
        }

        if (activeLayout) {
            handleActiveStatus(viewHolder, update);
        } else {
            handleNotActiveStatus(viewHolder, update);
        }
    }

    @Override
    public int getItemCount() {
        return mDownloadIds == null ? 0 : mDownloadIds.size();
    }

    public void setData(List<String> downloadIds) {
        mDownloadIds = downloadIds;
    }

    public void addItem(String downloadId) {
        if (mDownloadIds == null) {
            mDownloadIds = new ArrayList<>();
        }
        mDownloadIds.add(0, downloadId);
        notifyItemInserted(0);
    }

    public void notifyItemChanged(String downloadId) {
        if (mDownloadIds == null) {
            return;
        }
        int position = mDownloadIds.indexOf(downloadId);
        if (position < 0) {
            return;
        }
        notifyItemChanged(position);
    }

    public void removeItem(String downloadId) {
        if (mDownloadIds == null) {
            return;
        }
        int position = mDownloadIds.indexOf(downloadId);
        if (position < 0) {
            return;
        }
        mDownloadIds.remove(position);
        notifyItemRemoved(position);
        notifyItemRangeChanged(position, getItemCount());
    }

    public boolean containsDownloadId(String downloadId) {
        return mDownloadIds != null && mDownloadIds.contains(downloadId);
    }

    private void startDownloadWithWarning(final String downloadId) {
        UpdateInfo update = mUpdaterController.getUpdate(downloadId);
        if (update == null || update.getDownloadUrl() == null
                || update.getDownloadUrl().isEmpty()) {
            mActivity.showSnackbar(R.string.snack_download_failed, Snackbar.LENGTH_LONG);
            return;
        }
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mActivity);
        boolean warn = preferences.getBoolean(Constants.PREF_METERED_NETWORK_WARNING, true);
        if (!(Utils.isNetworkMetered(mActivity) && warn)) {
            mUpdaterController.startDownload(downloadId);
            return;
        }

        View checkboxView = LayoutInflater.from(mActivity).inflate(R.layout.checkbox_view, null);
        CheckBox checkbox = checkboxView.findViewById(R.id.checkbox);
        checkbox.setText(R.string.checkbox_metered_network_warning);

        new MaterialAlertDialogBuilder(mActivity)
                .setTitle(R.string.update_over_metered_network_title)
                .setMessage(R.string.update_over_metered_network_message)
                .setView(checkboxView)
                .setPositiveButton(R.string.action_download,
                        (dialog, which) -> {
                            if (checkbox.isChecked()) {
                                preferences.edit()
                                        .putBoolean(Constants.PREF_METERED_NETWORK_WARNING, false)
                                        .apply();
                                mActivity.supportInvalidateOptionsMenu();
                            }
                            mUpdaterController.startDownload(downloadId);
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private int getActionTitleRes(Action action) {
        switch (action) {
            case DOWNLOAD:
                return R.string.action_download;
            case PAUSE:
                return R.string.action_pause;
            case RESUME:
                return R.string.action_resume;
            case INSTALL:
                return R.string.action_install;
            case INFO:
                return R.string.action_info;
            case DELETE:
                return R.string.action_delete;
            case CANCEL_INSTALLATION:
                return R.string.action_cancel;
            case REBOOT:
                return R.string.reboot;
            default:
                return R.string.action_download;
        }
    }

    // Icon leading 24dp theo anatomy Menu.md
    private int getActionIconRes(Action action) {
        switch (action) {
            case DOWNLOAD:
                return R.drawable.ic_menu_download;
            case PAUSE:
                return R.drawable.ic_menu_pause;
            case RESUME:
                return R.drawable.ic_menu_play;
            case INSTALL:
                return R.drawable.ic_menu_install;
            case INFO:
                return R.drawable.ic_menu_info;
            case DELETE:
                return R.drawable.ic_menu_delete;
            case CANCEL_INSTALLATION:
                return R.drawable.ic_menu_cancel;
            case REBOOT:
                return R.drawable.ic_menu_reboot;
            default:
                return R.drawable.ic_menu_download;
        }
    }

    private void runPrimaryAction(Action action, final String downloadId) {
        switch (action) {
            case DOWNLOAD: {
                UpdateInfo update = mUpdaterController.getUpdate(downloadId);
                if (update == null || update.getDownloadUrl() == null
                        || update.getDownloadUrl().isEmpty()) {
                    mActivity.showSnackbar(R.string.snack_download_failed, Snackbar.LENGTH_LONG);
                    break;
                }
                startDownloadWithWarning(downloadId);
                break;
            }
            case PAUSE:
                mUpdaterController.pauseDownload(downloadId);
                break;
            case RESUME: {
                UpdateInfo update = mUpdaterController.getUpdate(downloadId);
                final boolean canInstall = Utils.canInstall(update) ||
                        update.getFile().length() == update.getFileSize();
                if (canInstall) {
                    mUpdaterController.resumeDownload(downloadId);
                } else {
                    mActivity.showSnackbar(R.string.snack_update_not_installable,
                            Snackbar.LENGTH_LONG);
                }
                break;
            }
            case INSTALL: {
                UpdateInfo update = mUpdaterController.getUpdate(downloadId);
                if (Utils.canInstall(update)) {
                    AlertDialog.Builder installDialog = getInstallDialog(downloadId);
                    if (installDialog != null) {
                        installDialog.show();
                    }
                } else {
                    mActivity.showSnackbar(R.string.snack_update_not_installable,
                            Snackbar.LENGTH_LONG);
                }
                break;
            }
            case INFO:
                showInfoDialog();
                break;
            case DELETE:
                getDeleteDialog(downloadId).show();
                break;
            case CANCEL_INSTALLATION:
                getCancelInstallationDialog().show();
                break;
            case REBOOT: {
                PowerManager pm = mActivity.getSystemService(PowerManager.class);
                pm.reboot(null);
                break;
            }
            default:
                break;
        }
    }

    private void bindExpandMenu(ViewHolder viewHolder, UpdateInfo update,
            Action primaryAction, boolean primaryEnabled, boolean canDelete) {
        viewHolder.mExpand.setEnabled(true);
        viewHolder.mExpand.setAlpha(1.f);
        if (primaryAction == Action.REBOOT) {
            // Cho reboot: chevron mo menu → nut mui ten xoay tron reboot
            viewHolder.mExpand.setImageResource(R.drawable.ic_menu_reboot);
            viewHolder.mExpand.setContentDescription(mActivity.getString(R.string.reboot));
            viewHolder.mExpand.setOnClickListener(
                    v -> runPrimaryAction(Action.REBOOT, update.getDownloadId()));
        } else {
            viewHolder.mExpand.setImageResource(R.drawable.ic_expand_chevron);
            viewHolder.mExpand.setContentDescription(
                    mActivity.getString(R.string.update_expand_options));
            viewHolder.mExpand.setOnClickListener(
                    v -> startActionMode(update, primaryAction, primaryEnabled, canDelete,
                            viewHolder.mExpand));
        }
    }

    private boolean isBusy() {
        return mUpdaterController.hasActiveDownloads() || mUpdaterController.isVerifyingUpdate()
                || mUpdaterController.isInstallingUpdate();
    }

    private AlertDialog.Builder getDeleteDialog(final String downloadId) {
        return new MaterialAlertDialogBuilder(mActivity)
                .setTitle(R.string.confirm_delete_dialog_title)
                .setMessage(R.string.confirm_delete_dialog_message)
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> {
                            mUpdaterController.pauseDownload(downloadId);
                            mUpdaterController.deleteUpdate(downloadId);
                        })
                .setNegativeButton(android.R.string.cancel, null);
    }

    private AlertDialog.Builder getInstallDialog(final String downloadId) {
        if (!isBatteryLevelOk()) {
            Resources resources = mActivity.getResources();
            String message = resources.getString(R.string.dialog_battery_low_message_pct,
                    resources.getInteger(R.integer.battery_ok_percentage_discharging),
                    resources.getInteger(R.integer.battery_ok_percentage_charging));
            return new MaterialAlertDialogBuilder(mActivity)
                    .setTitle(R.string.dialog_battery_low_title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null);
        }
        if (isScratchMounted()) {
            return new MaterialAlertDialogBuilder(mActivity)
                    .setTitle(R.string.dialog_scratch_mounted_title)
                    .setMessage(R.string.dialog_scratch_mounted_message)
                    .setPositiveButton(android.R.string.ok, null);
        }
        UpdateInfo update = mUpdaterController.getUpdate(downloadId);
        int resId;
        try {
            if (Utils.isABUpdate(update.getFile())) {
                resId = R.string.apply_update_dialog_message_ab;
            } else {
                resId = R.string.apply_update_dialog_message;
            }
        } catch (IOException e) {
            Log.e(TAG, "Could not determine the type of the update");
            return null;
        }

        String buildDate = StringGenerator.getDateLocalizedUTC(mActivity,
                DateFormat.MEDIUM, update.getTimestamp());
        String buildInfoText = mActivity.getString(R.string.list_build_version_date,
                update.getVersion(), buildDate);
        return new MaterialAlertDialogBuilder(mActivity)
                .setTitle(R.string.apply_update_dialog_title)
                .setMessage(mActivity.getString(resId, buildInfoText,
                        mActivity.getString(android.R.string.ok)))
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> {
                            Utils.triggerUpdate(mActivity, downloadId);
                            maybeShowInfoDialog();
                        })
                .setNegativeButton(android.R.string.cancel, null);
    }

    private AlertDialog.Builder getCancelInstallationDialog() {
        return new MaterialAlertDialogBuilder(mActivity)
                .setMessage(R.string.cancel_installation_dialog_message)
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> {
                            Intent intent = new Intent(mActivity, UpdaterService.class);
                            intent.setAction(UpdaterService.ACTION_INSTALL_STOP);
                            mActivity.startService(intent);
                        })
                .setNegativeButton(android.R.string.cancel, null);
    }

    private void maybeShowInfoDialog() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mActivity);
        boolean alreadySeen = preferences.getBoolean(Constants.HAS_SEEN_INFO_DIALOG, false);
        if (alreadySeen) {
            return;
        }
        new MaterialAlertDialogBuilder(mActivity)
                .setTitle(R.string.info_dialog_title)
                .setMessage(R.string.info_dialog_message)
                .setPositiveButton(R.string.info_dialog_ok, (dialog, which) -> preferences.edit()
                        .putBoolean(Constants.HAS_SEEN_INFO_DIALOG, true)
                        .apply())
                .show();
    }

    private void startActionMode(final UpdateInfo update, final Action primaryAction,
            final boolean primaryEnabled, final boolean canDelete, View anchor) {
        mSelectedDownload = update.getDownloadId();
        notifyItemChanged(update.getDownloadId());

        // PopupMenu.show() moi ap dung popupMenuStyle / popupMenuBackground.
        ContextThemeWrapper wrapper = new ContextThemeWrapper(mActivity,
                R.style.AppTheme_PopupMenuOverlapAnchor);
        PopupMenu popupMenu = new PopupMenu(wrapper, anchor, Gravity.CENTER,
                R.attr.popupMenuStyle, 0);
        popupMenu.inflate(R.menu.menu_action_mode);
        popupMenu.setForceShowIcon(true);

        boolean shouldShowDelete = canDelete;
        boolean isVerified = update.getPersistentStatus() == UpdateStatus.Persistent.VERIFIED;
        if (isVerified && !Utils.canInstall(update) && !update.getAvailableOnline()) {
            shouldShowDelete = false;
        }
        // Tranh trung Delete khi primary da la DELETE
        if (primaryAction == Action.DELETE) {
            shouldShowDelete = false;
        }

        MenuItem primaryItem = popupMenu.getMenu().findItem(R.id.menu_primary_action);
        primaryItem.setTitle(getActionTitleRes(primaryAction));
        primaryItem.setIcon(getActionIconRes(primaryAction));
        primaryItem.setEnabled(primaryEnabled);
        primaryItem.setVisible(true);

        popupMenu.getMenu().findItem(R.id.menu_delete_action).setVisible(shouldShowDelete);
        popupMenu.getMenu().findItem(R.id.menu_copy_url).setVisible(update.getAvailableOnline());
        popupMenu.getMenu().findItem(R.id.menu_export_update).setVisible(isVerified);

        popupMenu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.menu_primary_action) {
                if (primaryEnabled) {
                    runPrimaryAction(primaryAction, update.getDownloadId());
                }
                return true;
            } else if (itemId == R.id.menu_delete_action) {
                getDeleteDialog(update.getDownloadId()).show();
                return true;
            } else if (itemId == R.id.menu_copy_url) {
                Utils.addToClipboard(mActivity,
                        mActivity.getString(R.string.label_download_url),
                        update.getDownloadUrl(),
                        mActivity.getString(R.string.toast_download_url_copied));
                return true;
            } else if (itemId == R.id.menu_export_update) {
                if (mActivity != null) {
                    mActivity.exportUpdate(update);
                }
                return true;
            }
            return false;
        });
        popupMenu.setOnDismissListener(menu -> {
            mSelectedDownload = null;
            notifyItemChanged(update.getDownloadId());
        });
        popupMenu.show();
    }

    private void showInfoDialog() {
        if (infoDialog != null) {
            infoDialog.dismiss();
        }
        infoDialog = new MaterialAlertDialogBuilder(mActivity)
                .setTitle(R.string.blocked_update_dialog_title)
                .setPositiveButton(android.R.string.ok, null)
                .setMessage(R.string.blocked_update_dialog_message_custom)
                .show();
        TextView textView = infoDialog.findViewById(android.R.id.message);
        if (textView != null) {
            textView.setMovementMethod(LinkMovementMethod.getInstance());
        }
    }

    private boolean isBatteryLevelOk() {
        Intent intent = mActivity.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (intent == null || !intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)) {
            return true;
        }
        int percent = Math.round(100.f * intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) /
                intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
        int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int required = (plugged & BATTERY_PLUGGED_ANY) != 0 ?
                mActivity.getResources().getInteger(R.integer.battery_ok_percentage_charging) :
                mActivity.getResources().getInteger(R.integer.battery_ok_percentage_discharging);
        return percent >= required;
    }

    private static boolean isScratchMounted() {
        try (Stream<String> lines = Files.lines(Path.of("/proc/mounts"))) {
            return lines.anyMatch(x -> x.split(" ")[1].equals("/mnt/scratch"));
        } catch (IOException e) {
            return false;
        }
    }
}
