/*
 * Copyright (C) 2017-2023 The LineageOS Project
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

import android.app.Activity;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.icu.text.DateFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.os.SystemProperties;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;

import org.json.JSONException;
import org.evolution.updater.controller.UpdaterController;
import org.evolution.updater.controller.UpdaterService;
import org.evolution.updater.download.DownloadClient;
import org.evolution.updater.misc.BuildInfoUtils;
import org.evolution.updater.misc.Constants;
import org.evolution.updater.misc.LocaleHelper;
import org.evolution.updater.misc.StringGenerator;
import org.evolution.updater.misc.Utils;
import org.evolution.updater.model.Update;
import org.evolution.updater.model.UpdateInfo;
import org.evolution.updater.ui.AboutFragment;
import org.evolution.updater.ui.ChangelogFragment;
import org.evolution.updater.ui.HomeFragment;
import org.evolution.updater.ui.SettingsFragment;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class UpdatesActivity extends UpdatesListActivity
        implements UpdateImporter.Callbacks, UpdatesHostCallback {

    private static final String TAG = "UpdatesActivity";
    private UpdaterService mUpdaterService;
    private BroadcastReceiver mBroadcastReceiver;

    private UpdatesListAdapter mAdapter;
    private UpdatesListAdapter mLocalAdapter;

    private boolean mIsTV;

    // Thu tu tab ViewPager2 == bottom nav
    private static final int PAGE_HOME = 0;
    private static final int PAGE_CHANGELOG = 1;
    private static final int PAGE_SETTINGS = 2;
    private static final int PAGE_ABOUT = 3;

    private ViewPager2 mViewPager;
    private HomeFragment mHomeFragment;
    private ChangelogFragment mChangelogFragment;
    private SettingsFragment mSettingsFragment;
    private AboutFragment mAboutFragment;

    private UpdateInfo mToBeExported = null;
    private final ActivityResultLauncher<Intent> mExportUpdate = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    Intent intent = result.getData();
                    if (intent != null) {
                        Uri uri = intent.getData();
                        exportUpdate(uri);
                    }
                }
            });

    private UpdateImporter mUpdateImporter;
    private AlertDialog importDialog;

    // The thong bao Material (SurfaceBright) — thay Snackbar inverse
    private static final long TOAST_DURATION_SHORT_MS = 2000L;
    private static final long TOAST_DURATION_LONG_MS = 3500L;
    private static final float TOAST_ENTER_TRANSLATION_DP = 24f;
    private final Handler mToastHandler = new Handler(Looper.getMainLooper());
    private View mToastCard;
    private Runnable mToastDismissRunnable;

    @Override
    protected void attachBaseContext(Context newBase) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(newBase);
        String tag = prefs.getString(Constants.PREF_APP_LOCALE, "");
        super.attachBaseContext(LocaleHelper.wrap(newBase, tag != null ? tag : ""));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Monet kieu ReSukiSU: seed system_accent* qua ThemeOverlay, applyStyle
        // (khong DynamicColors.apply* — tranh crash Soong AppCompat).
        applyDynamicColorOverlay();
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_updates);
        applyEdgeToEdgeInsets();

        mUpdateImporter = new UpdateImporter(this, this);

        UiModeManager uiModeManager = getSystemService(UiModeManager.class);
        mIsTV = uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;

        if (mIsTV) {
            mAdapter = new UpdatesListAdapter(this);
        } else {
            // Phone: item phang trong the Home; tach local / online
            mAdapter = new UpdatesListAdapter(this, R.layout.update_item_embedded);
            mLocalAdapter = new UpdatesListAdapter(this, R.layout.update_item_embedded);
        }

        if (mIsTV) {
            setupTvUi();
        } else {
            setupPhoneUi(PAGE_HOME);
        }

        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (UpdaterController.ACTION_UPDATE_STATUS.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    handleDownloadStatusChange(downloadId);
                    notifyAdaptersItemChanged(downloadId);
                } else if (UpdaterController.ACTION_DOWNLOAD_PROGRESS.equals(intent.getAction()) ||
                        UpdaterController.ACTION_INSTALL_PROGRESS.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    notifyAdaptersItemChanged(downloadId);
                } else if (UpdaterController.ACTION_UPDATE_REMOVED.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    mAdapter.removeItem(downloadId);
                    if (mLocalAdapter != null) {
                        mLocalAdapter.removeItem(downloadId);
                    }
                    refreshHomeListVisibility();
                }
            }
        };

        updateHeaderInfo();
        maybeShowWelcomeMessage();
    }

    /**
     * Setup UI phone: ViewPager + bottom nav. initialTab = tab sau soft-refresh locale.
     */
    private void setupPhoneUi(int initialTab) {
        // Fade day noi dung: transparent -> colorSurfaceContainer (Dynamic Color)
        View fade = findViewById(R.id.bottom_content_fade);
        if (fade != null) {
            int surface = MaterialColors.getColor(this,
                    com.google.android.material.R.attr.colorSurfaceContainer, 0);
            GradientDrawable gd = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] { Color.TRANSPARENT, surface });
            fade.setBackground(gd);
        }

        // Instance moi — updateHeaderInfo / adapters van goi duoc qua field
        mHomeFragment = new HomeFragment();
        mChangelogFragment = new ChangelogFragment();
        mSettingsFragment = new SettingsFragment();
        mAboutFragment = new AboutFragment();

        mViewPager = findViewById(R.id.view_pager);
        // Giu 4 trang trong bo nho — tranh mat ref Activity khi swipe
        mViewPager.setOffscreenPageLimit(3);
        mViewPager.setAdapter(createPagerAdapter());

        BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
        // Bottom nav → ViewPager (co animation ngang)
        bottomNav.setOnItemSelectedListener(item -> {
            int page = navIdToPage(item.getItemId());
            if (page < 0) {
                return false;
            }
            if (mViewPager.getCurrentItem() != page) {
                mViewPager.setCurrentItem(page, true);
            }
            return true;
        });

        // Swipe ngang → chi sync bottom nav (title nam trong tung fragment)
        mViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                int navId = pageToNavId(position);
                MenuItem menuItem = bottomNav.getMenu().findItem(navId);
                if (menuItem != null && !menuItem.isChecked()) {
                    menuItem.setChecked(true);
                }
            }
        });

        int tab = initialTab;
        if (tab < PAGE_HOME || tab > PAGE_ABOUT) {
            tab = PAGE_HOME;
        }
        if (tab != PAGE_HOME) {
            mViewPager.setCurrentItem(tab, false);
        }
        int navId = pageToNavId(tab);
        MenuItem menuItem = bottomNav.getMenu().findItem(navId);
        if (menuItem != null) {
            menuItem.setChecked(true);
        }
    }

    private FragmentStateAdapter createPagerAdapter() {
        return new FragmentStateAdapter(this) {
            @Override
            public int getItemCount() {
                return 4;
            }

            @NonNull
            @Override
            public Fragment createFragment(int position) {
                switch (position) {
                    case PAGE_CHANGELOG:
                        return mChangelogFragment;
                    case PAGE_SETTINGS:
                        return mSettingsFragment;
                    case PAGE_ABOUT:
                        return mAboutFragment;
                    case PAGE_HOME:
                    default:
                        return mHomeFragment;
                }
            }
        };
    }

    /**
     * Soft-refresh locale: cung Activity/Window — updateConfiguration + setContentView
     * + setup lai UI. Khong finish / startActivity / recreate / fade.
     * BroadcastReceiver / ServiceConnection giu nguyen (khong re-bind).
     */
    public void applyLocaleStringsOnly(String tag) {
        int tab = (mViewPager != null) ? mViewPager.getCurrentItem() : PAGE_SETTINGS;

        dismissToastCard(false);
        LocaleHelper.applyAppAndActivity(getApplication(), this, tag != null ? tag : "");

        // Go adapter + xoa fragment cu — tranh FM giu fragment sau setContentView
        if (mViewPager != null) {
            mViewPager.setAdapter(null);
        }
        clearSupportFragments();

        setContentView(R.layout.activity_updates);
        applyEdgeToEdgeInsets();

        if (mIsTV) {
            setupTvUi();
        } else {
            setupPhoneUi(tab);
        }

        // Service van bind — gan lai controller cho adapter + refresh list/header
        if (mUpdaterService != null) {
            mAdapter.setUpdaterController(mUpdaterService.getUpdaterController());
            if (mLocalAdapter != null) {
                mLocalAdapter.setUpdaterController(mUpdaterService.getUpdaterController());
            }
            getUpdatesList();
        }
        updateHeaderInfo();
    }

    /** Xoa fragment con trong FM truoc khi inflate hierarchy moi. */
    private void clearSupportFragments() {
        FragmentManager fm = getSupportFragmentManager();
        List<Fragment> existing = fm.getFragments();
        if (existing.isEmpty()) {
            return;
        }
        FragmentTransaction ft = fm.beginTransaction();
        boolean removed = false;
        for (Fragment f : existing) {
            if (f != null) {
                ft.remove(f);
                removed = true;
            }
        }
        if (removed) {
            ft.commitNowAllowingStateLoss();
        }
    }

    private static int navIdToPage(int navItemId) {
        if (navItemId == R.id.nav_home) {
            return PAGE_HOME;
        } else if (navItemId == R.id.nav_changelog) {
            return PAGE_CHANGELOG;
        } else if (navItemId == R.id.nav_settings) {
            return PAGE_SETTINGS;
        } else if (navItemId == R.id.nav_about) {
            return PAGE_ABOUT;
        }
        return -1;
    }

    private static int pageToNavId(int page) {
        switch (page) {
            case PAGE_CHANGELOG:
                return R.id.nav_changelog;
            case PAGE_SETTINGS:
                return R.id.nav_settings;
            case PAGE_ABOUT:
                return R.id.nav_about;
            case PAGE_HOME:
            default:
                return R.id.nav_home;
        }
    }

    private void setupTvUi() {
        RecyclerView recyclerView = findViewById(R.id.recycler_view);
        recyclerView.setAdapter(mAdapter);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        RecyclerView.ItemAnimator animator = recyclerView.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }

        findViewById(R.id.refresh).setOnClickListener(v -> downloadUpdatesList(true));
        findViewById(R.id.preferences).setOnClickListener(v -> showTvPreferencesDialog());
    }

    private void setUpdatesListVisible(boolean hasOnlineUpdates) {
        if (mIsTV) {
            findViewById(R.id.no_new_updates_view).setVisibility(
                    hasOnlineUpdates ? View.GONE : View.VISIBLE);
            findViewById(R.id.recycler_view).setVisibility(
                    hasOnlineUpdates ? View.VISIBLE : View.GONE);
        } else if (mHomeFragment != null) {
            mHomeFragment.setUpdatesVisible(hasOnlineUpdates);
            int count = mAdapter != null ? mAdapter.getItemCount() : 0;
            mHomeFragment.bindUpdateStatus(hasOnlineUpdates, count);
        }
    }

    private void refreshHomeListVisibility() {
        boolean hasOnline = mAdapter != null && mAdapter.getItemCount() > 0;
        setUpdatesListVisible(hasOnline);
        if (mHomeFragment != null) {
            boolean hasLocal = mLocalAdapter != null && mLocalAdapter.getItemCount() > 0;
            mHomeFragment.setLocalUpdateVisible(hasLocal);
        }
    }

    private void notifyAdaptersItemChanged(String downloadId) {
        mAdapter.notifyItemChanged(downloadId);
        if (mLocalAdapter != null) {
            mLocalAdapter.notifyItemChanged(downloadId);
        }
    }

    /** Gan danh sach OTA: phone tach online / local; TV giu 1 list. */
    private void applySortedUpdates(List<UpdateInfo> sortedUpdates) {
        if (sortedUpdates == null) {
            sortedUpdates = new ArrayList<>();
        }
        sortedUpdates.sort((u1, u2) -> Long.compare(u2.getTimestamp(), u1.getTimestamp()));

        if (mIsTV || mLocalAdapter == null) {
            List<String> updateIds = new ArrayList<>();
            for (UpdateInfo update : sortedUpdates) {
                updateIds.add(update.getDownloadId());
            }
            mAdapter.setData(updateIds);
            mAdapter.notifyDataSetChanged();
            setUpdatesListVisible(!updateIds.isEmpty());
            return;
        }

        List<String> onlineIds = new ArrayList<>();
        List<String> localIds = new ArrayList<>();
        for (UpdateInfo update : sortedUpdates) {
            if (Update.LOCAL_ID.equals(update.getDownloadId())) {
                localIds.add(update.getDownloadId());
            } else {
                onlineIds.add(update.getDownloadId());
            }
        }
        mAdapter.setData(onlineIds);
        mAdapter.notifyDataSetChanged();
        mLocalAdapter.setData(localIds);
        mLocalAdapter.notifyDataSetChanged();
        setUpdatesListVisible(!onlineIds.isEmpty());
        if (mHomeFragment != null) {
            mHomeFragment.setLocalUpdateVisible(!localIds.isEmpty());
        }
    }

    /**
     * Monet giong ReSukiSU (system_accent*): overlay Material3 DynamicColors
     * bang applyStyle — khong goi DynamicColors.applyToActivitiesIfAvailable.
     */
    private void applyDynamicColorOverlay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }
        getTheme().applyStyle(
                com.google.android.material.R.style.ThemeOverlay_Material3_DynamicColors_DayNight,
                true);
    }

    private void applyEdgeToEdgeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_container),
                (view, windowInsets) -> {
                    Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                    // Pad top ViewPager (status bar); moi fragment AppBar nam trong do
                    ViewPager2 pager = findViewById(R.id.view_pager);
                    if (pager != null) {
                        pager.setPadding(pager.getPaddingLeft(), bars.top,
                                pager.getPaddingRight(), pager.getPaddingBottom());
                    }
                    BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
                    if (bottomNav != null) {
                        bottomNav.setPadding(bottomNav.getPaddingLeft(), bottomNav.getPaddingTop(),
                                bottomNav.getPaddingRight(), bars.bottom);
                    }
                    return WindowInsetsCompat.CONSUMED;
                });
    }

    @Override
    public void onStart() {
        super.onStart();
        Intent intent = new Intent(this, UpdaterService.class);
        startService(intent);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_STATUS);
        intentFilter.addAction(UpdaterController.ACTION_DOWNLOAD_PROGRESS);
        intentFilter.addAction(UpdaterController.ACTION_INSTALL_PROGRESS);
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_REMOVED);
        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, intentFilter);
    }

    @Override
    protected void onPause() {
        if (importDialog != null) {
            importDialog.dismiss();
            importDialog = null;
            mUpdateImporter.stopImport();
        }
        super.onPause();
    }

    @Override
    public void onStop() {
        dismissToastCard(false);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mBroadcastReceiver);
        if (mUpdaterService != null) {
            unbindService(mConnection);
        }
        super.onStop();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (!mUpdateImporter.onResult(requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onImportStarted() {
        if (importDialog != null && importDialog.isShowing()) {
            importDialog.dismiss();
        }

        importDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.local_update_import)
                .setView(R.layout.progress_dialog)
                .setCancelable(false)
                .create();
        importDialog.show();
    }

    @Override
    public void onImportCompleted(Update update) {
        if (importDialog != null) {
            importDialog.dismiss();
            importDialog = null;
        }

        if (update == null) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.local_update_import)
                    .setMessage(R.string.local_update_import_failure)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        mAdapter.notifyDataSetChanged();
        if (mLocalAdapter != null) {
            // Local vua import — dua vao the "Cap nhat tu bo nho"
            if (!mLocalAdapter.containsDownloadId(update.getDownloadId())) {
                mLocalAdapter.addItem(update.getDownloadId());
            } else {
                mLocalAdapter.notifyItemChanged(update.getDownloadId());
            }
            if (mHomeFragment != null) {
                mHomeFragment.setLocalUpdateVisible(true);
            }
        }

        // Cancel / De sau: giu ban da import trong controller + adapter (khong deleteUpdate)
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.local_update_import)
                .setMessage(getString(R.string.local_update_import_success, update.getVersion()))
                .setPositiveButton(R.string.local_update_import_install, (dialog, which) -> {
                    // Item local da add truoc dialog; chi cai ngay
                    getUpdatesList();
                    Utils.triggerUpdate(this, update.getDownloadId());
                })
                .setNegativeButton(R.string.local_update_import_later, null)
                .show();
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            UpdaterService.LocalBinder binder = (UpdaterService.LocalBinder) service;
            mUpdaterService = binder.getService();
            mAdapter.setUpdaterController(mUpdaterService.getUpdaterController());
            if (mLocalAdapter != null) {
                mLocalAdapter.setUpdaterController(mUpdaterService.getUpdaterController());
            }
            getUpdatesList();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mAdapter.setUpdaterController(null);
            if (mLocalAdapter != null) {
                mLocalAdapter.setUpdaterController(null);
            }
            mUpdaterService = null;
            mAdapter.notifyDataSetChanged();
            if (mLocalAdapter != null) {
                mLocalAdapter.notifyDataSetChanged();
            }
        }
    };

    private void loadUpdatesList(File jsonFile, boolean manualRefresh)
            throws IOException, JSONException {
        Log.d(TAG, "Adding remote updates");
        UpdaterController controller = mUpdaterService.getUpdaterController();
        boolean newUpdates = false;

        List<UpdateInfo> updates = Utils.parseJson(jsonFile, true);
        List<String> updatesOnline = new ArrayList<>();
        for (UpdateInfo update : updates) {
            newUpdates |= controller.addUpdate(update);
            updatesOnline.add(update.getDownloadId());
        }
        controller.setUpdatesAvailableOnline(updatesOnline, true);

        if (manualRefresh) {
            showSnackbar(
                    newUpdates ? R.string.snack_updates_found : R.string.snack_no_updates_found,
                    Snackbar.LENGTH_SHORT);
        }

        List<UpdateInfo> sortedUpdates = controller.getUpdates();
        applySortedUpdates(sortedUpdates);
        updateHeaderInfo();
    }

    private void getUpdatesList() {
        File jsonFile = Utils.getCachedUpdateList(this);
        if (jsonFile.exists()) {
            try {
                loadUpdatesList(jsonFile, false);
                Log.d(TAG, "Cached list parsed");
            } catch (IOException | JSONException e) {
                Log.e(TAG, "Error while parsing json list", e);
            }
        } else {
            downloadUpdatesList(false);
        }
    }

    private void processNewJson(File json, File jsonNew, boolean manualRefresh) {
        try {
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
            long millis = System.currentTimeMillis();
            preferences.edit().putLong(Constants.PREF_LAST_UPDATE_CHECK, millis).apply();
            loadUpdatesList(jsonNew, manualRefresh);
            if (json.exists() && Utils.isUpdateCheckEnabled(this) &&
                    Utils.checkForNewUpdates(json, jsonNew)) {
                UpdatesCheckReceiver.updateRepeatingUpdatesCheck(this);
            }
            UpdatesCheckReceiver.cancelUpdatesCheck(this);
            //noinspection ResultOfMethodCallIgnored
            jsonNew.renameTo(json);
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Could not read json", e);
            showSnackbar(R.string.snack_updates_check_failed, Snackbar.LENGTH_LONG);
        }
    }

    @Override
    public void downloadUpdatesList(final boolean manualRefresh) {
        final File jsonFile = Utils.getCachedUpdateList(this);
        final File jsonFileTmp = new File(jsonFile.getAbsolutePath() + UUID.randomUUID());
        String url = Utils.getServerURL(this);
        Log.d(TAG, "Checking " + url);

        DownloadClient.DownloadCallback callback = new DownloadClient.DownloadCallback() {
            @Override
            public void onFailure(final boolean cancelled) {
                Log.e(TAG, "Could not download updates list");
                runOnUiThread(() -> {
                    if (!cancelled) {
                        showSnackbar(R.string.snack_updates_check_failed, Snackbar.LENGTH_LONG);
                    }
                    refreshAnimationStop();
                });
            }

            @Override
            public void onResponse(DownloadClient.Headers headers) {
            }

            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    Log.d(TAG, "List downloaded");
                    processNewJson(jsonFile, jsonFileTmp, manualRefresh);
                    refreshAnimationStop();
                });
            }
        };

        final DownloadClient downloadClient;
        try {
            downloadClient = new DownloadClient.Builder()
                    .setUrl(url)
                    .setDestination(jsonFileTmp)
                    .setDownloadCallback(callback)
                    .build();
        } catch (IOException exception) {
            Log.e(TAG, "Could not build download client");
            showSnackbar(R.string.snack_updates_check_failed, Snackbar.LENGTH_LONG);
            return;
        }

        refreshAnimationStart();
        downloadClient.start();
    }

    @Override
    public void updateHeaderInfo() {
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(this);
        long lastCheck = preferences.getLong(Constants.PREF_LAST_UPDATE_CHECK, -1) / 1000;
        String lastCheckString = getString(R.string.header_last_updates_check,
                StringGenerator.getDateLocalized(this, DateFormat.LONG, lastCheck),
                StringGenerator.getTimeLocalized(this, lastCheck));
        // ROM version noi bat (vd. Evolution X 12.2); Android chi phu
        String romVersion = getString(R.string.list_build_version, BuildInfoUtils.getBuildVersion());
        String androidVersion = getString(R.string.header_android_version, Build.VERSION.RELEASE);
        String localizedBuildDate = StringGenerator.getDateLocalizedUTC(this, DateFormat.LONG,
                BuildInfoUtils.getBuildDateTimestamp());
        // Home: 1 dong ngay sach (home_build_date); TV/About giu current_build_date
        String homeBuildDate = getString(R.string.home_build_date, localizedBuildDate);
        String buildDate = getString(R.string.current_build_date, localizedBuildDate);
        String maintainer = getString(R.string.maintainer_name,
                getString(R.string.default_maintainer_name));

        if (mIsTV) {
            TextView headerLastCheck = findViewById(R.id.header_last_check);
            headerLastCheck.setText(lastCheckString);

            TextView headerBuildVersion = findViewById(R.id.header_build_version);
            headerBuildVersion.setText(romVersion);

            TextView headerBuildDate = findViewById(R.id.header_build_date);
            headerBuildDate.setText(buildDate);

            TextView maintainerName = findViewById(R.id.maintainer_name);
            LinearLayout supportLayout = findViewById(R.id.support_icons);
            maintainerName.setText(maintainer);
            maintainerName.setVisibility(View.VISIBLE);
            supportLayout.setVisibility(View.VISIBLE);

            ImageView forumImage = findViewById(R.id.support_forum);
            final String forumUrl = getString(R.string.support_forum_url);
            forumImage.setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(forumUrl));
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
                startActivity(intent);
            });

            ImageView sourceImage = findViewById(R.id.support_source);
            final String sourceUrl = getString(R.string.support_source_url);
            sourceImage.setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(sourceUrl));
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
                startActivity(intent);
            });
        } else {
            if (mHomeFragment != null) {
                mHomeFragment.bindHeader(romVersion, androidVersion, homeBuildDate,
                        lastCheckString);
            }
            if (mAboutFragment != null) {
                // Android / ROM + ngay / kernel (kernel tu bind trong fragment)
                mAboutFragment.bindBuildInfo(androidVersion, romVersion, homeBuildDate);
            }
        }
    }

    private void handleDownloadStatusChange(String downloadId) {
        if (Update.LOCAL_ID.equals(downloadId)) {
            return;
        }

        UpdateInfo update = mUpdaterService.getUpdaterController().getUpdate(downloadId);
        switch (update.getStatus()) {
            case PAUSED_ERROR:
                showSnackbar(R.string.snack_download_failed, Snackbar.LENGTH_LONG);
                break;
            case VERIFICATION_FAILED:
                showSnackbar(R.string.snack_download_verification_failed, Snackbar.LENGTH_LONG);
                break;
            case VERIFIED:
                showSnackbar(R.string.snack_download_verified, Snackbar.LENGTH_LONG);
                break;
        }
    }

    @Override
    public void exportUpdate(UpdateInfo update) {
        mToBeExported = update;

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, update.getName());

        mExportUpdate.launch(intent);
    }

    private void exportUpdate(Uri uri) {
        Intent intent = new Intent(this, ExportUpdateService.class);
        intent.setAction(ExportUpdateService.ACTION_START_EXPORTING);
        intent.putExtra(ExportUpdateService.EXTRA_SOURCE_FILE, mToBeExported.getFile());
        intent.putExtra(ExportUpdateService.EXTRA_DEST_URI, uri);
        startService(intent);
    }

    @Override
    public void showSnackbar(int stringId, int duration) {
        // The Material (Widget.Updater.Card): SurfaceBright + colorOnSurface, khong InverseSurface
        ViewGroup host = findViewById(R.id.toast_host);
        if (host == null) {
            // Fallback TV/cu neu thieu host
            Snackbar.make(getSnackbarAnchor(), stringId, duration).show();
            return;
        }

        dismissToastCard(false);

        float density = getResources().getDisplayMetrics().density;
        int margin = Math.round(16 * density);
        int statusBarInset = 0;
        WindowInsetsCompat rootInsets = ViewCompat.getRootWindowInsets(host);
        if (rootInsets != null) {
            statusBarInset = rootInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
        }
        int top = statusBarInset + margin;

        View card = LayoutInflater.from(this).inflate(R.layout.popup_toast_card, host, false);
        TextView message = card.findViewById(R.id.popup_toast_message);
        message.setText(stringId);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.setMargins(margin, top, margin, margin);
        host.addView(card, lp);
        mToastCard = card;

        float enterTy = -TOAST_ENTER_TRANSLATION_DP * density;
        card.setAlpha(0f);
        card.setTranslationY(enterTy);
        card.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(200)
                .start();

        card.setOnClickListener(v -> dismissToastCard(true));

        long dismissMs = resolveToastDurationMs(duration);
        if (dismissMs > 0) {
            mToastDismissRunnable = () -> dismissToastCard(true);
            mToastHandler.postDelayed(mToastDismissRunnable, dismissMs);
        }
    }

    private long resolveToastDurationMs(int duration) {
        if (duration == Snackbar.LENGTH_INDEFINITE) {
            return 0L;
        }
        if (duration == Snackbar.LENGTH_SHORT) {
            return TOAST_DURATION_SHORT_MS;
        }
        if (duration == Snackbar.LENGTH_LONG) {
            return TOAST_DURATION_LONG_MS;
        }
        // Gia tri duong = milliseconds tuy chinh
        return duration > 0 ? duration : TOAST_DURATION_SHORT_MS;
    }

    private void dismissToastCard(boolean animate) {
        if (mToastDismissRunnable != null) {
            mToastHandler.removeCallbacks(mToastDismissRunnable);
            mToastDismissRunnable = null;
        }
        final View card = mToastCard;
        mToastCard = null;
        if (card == null) {
            return;
        }
        ViewGroup parent = (ViewGroup) card.getParent();
        if (!animate || parent == null) {
            if (parent != null) {
                parent.removeView(card);
            }
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        card.animate()
                .alpha(0f)
                .translationY(-TOAST_ENTER_TRANSLATION_DP * density)
                .setDuration(150)
                .withEndAction(() -> {
                    ViewGroup p = (ViewGroup) card.getParent();
                    if (p != null) {
                        p.removeView(card);
                    }
                })
                .start();
    }

    private void refreshAnimationStart() {
        if (mIsTV) {
            findViewById(R.id.recycler_view).setVisibility(View.GONE);
            findViewById(R.id.no_new_updates_view).setVisibility(View.GONE);
            findViewById(R.id.refresh_progress).setVisibility(View.VISIBLE);
        } else if (mHomeFragment != null) {
            mHomeFragment.setRefreshing(true);
        }
    }

    private void refreshAnimationStop() {
        if (mIsTV) {
            findViewById(R.id.refresh_progress).setVisibility(View.GONE);
            if (mAdapter.getItemCount() > 0) {
                findViewById(R.id.recycler_view).setVisibility(View.VISIBLE);
            } else {
                findViewById(R.id.no_new_updates_view).setVisibility(View.VISIBLE);
            }
        } else if (mHomeFragment != null) {
            mHomeFragment.setRefreshing(false);
        }
    }

    private void maybeShowWelcomeMessage() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean alreadySeen = preferences.getBoolean(Constants.HAS_SEEN_WELCOME_MESSAGE, false);
        if (alreadySeen) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.welcome_title)
                .setMessage(R.string.welcome_message)
                .setPositiveButton(R.string.info_dialog_ok, (dialog, which) -> preferences.edit()
                        .putBoolean(Constants.HAS_SEEN_WELCOME_MESSAGE, true)
                        .apply())
                .show();
    }

    // --- UpdatesHostCallback ---

    @Override
    public UpdaterService getUpdaterService() {
        return mUpdaterService;
    }

    @Override
    public UpdatesListAdapter getUpdatesAdapter() {
        return mAdapter;
    }

    @Override
    public UpdatesListAdapter getLocalUpdatesAdapter() {
        return mLocalAdapter;
    }

    @Override
    public void onImportLocalUpdate() {
        mUpdateImporter.openImportPicker();
    }

    @Override
    public View getSnackbarAnchor() {
        return findViewById(R.id.main_container);
    }

    // TV khong co tab Settings — giu bottom sheet preferences cu
    private void showTvPreferencesDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.preferences_dialog, null);
        AutoCompleteTextView autoCheckInterval = view.findViewById(
                R.id.preferences_auto_updates_check_interval);
        MaterialSwitch autoDelete = view.findViewById(R.id.preferences_auto_delete_updates);
        MaterialSwitch meteredNetworkWarning = view.findViewById(
                R.id.preferences_metered_network_warning);
        MaterialSwitch abPerfMode = view.findViewById(R.id.preferences_ab_perf_mode);
        MaterialSwitch updateRecovery = view.findViewById(R.id.preferences_update_recovery);
        View abPerfModeRow = view.findViewById(R.id.preferences_ab_perf_mode_row);
        View updateRecoveryRow = view.findViewById(R.id.preferences_update_recovery_row);

        final String[] intervalEntries = getResources().getStringArray(
                R.array.menu_auto_updates_check_interval_entries);
        final int[] selectedInterval = {Utils.getUpdateCheckSetting(this)};
        ArrayAdapter<String> intervalAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, intervalEntries);
        autoCheckInterval.setAdapter(intervalAdapter);
        autoCheckInterval.setText(intervalEntries[selectedInterval[0]], false);
        autoCheckInterval.setOnItemClickListener((parent, itemView, position, id) ->
                selectedInterval[0] = position);

        if (!Utils.isABDevice()) {
            abPerfModeRow.setVisibility(View.GONE);
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        autoDelete.setChecked(prefs.getBoolean(Constants.PREF_AUTO_DELETE_UPDATES, false));
        meteredNetworkWarning.setChecked(prefs.getBoolean(Constants.PREF_METERED_NETWORK_WARNING,
                prefs.getBoolean(Constants.PREF_MOBILE_DATA_WARNING, true)));
        abPerfMode.setChecked(prefs.getBoolean(Constants.PREF_AB_PERF_MODE, false));

        if (getResources().getBoolean(R.bool.config_hideRecoveryUpdate)) {
            updateRecoveryRow.setVisibility(View.GONE);
        } else if (Utils.isRecoveryUpdateExecPresent()) {
            updateRecovery.setChecked(
                    SystemProperties.getBoolean(Constants.UPDATE_RECOVERY_PROPERTY, false));
        } else {
            updateRecovery.setChecked(true);
            updateRecovery.setOnTouchListener(new View.OnTouchListener() {
                private Toast forcedUpdateToast = null;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (forcedUpdateToast != null) {
                        forcedUpdateToast.cancel();
                    }
                    forcedUpdateToast = Toast.makeText(getApplicationContext(),
                            getString(R.string.toast_forced_update_recovery), Toast.LENGTH_SHORT);
                    forcedUpdateToast.show();
                    return true;
                }
            });
        }

        BottomSheetDialog sheet = new BottomSheetDialog(this);
        sheet.setContentView(view);
        sheet.setOnShowListener(dialogInterface -> {
            View bottomSheet = sheet.findViewById(
                    com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(bottomSheet);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                behavior.setSkipCollapsed(true);
            }
        });
        sheet.setOnDismissListener(dialogInterface -> {
            prefs.edit()
                    .putInt(Constants.PREF_AUTO_UPDATES_CHECK_INTERVAL, selectedInterval[0])
                    .putBoolean(Constants.PREF_AUTO_DELETE_UPDATES, autoDelete.isChecked())
                    .putBoolean(Constants.PREF_METERED_NETWORK_WARNING,
                            meteredNetworkWarning.isChecked())
                    .putBoolean(Constants.PREF_AB_PERF_MODE, abPerfMode.isChecked())
                    .apply();

            if (Utils.isUpdateCheckEnabled(this)) {
                UpdatesCheckReceiver.scheduleRepeatingUpdatesCheck(this);
            } else {
                UpdatesCheckReceiver.cancelRepeatingUpdatesCheck(this);
                UpdatesCheckReceiver.cancelUpdatesCheck(this);
            }

            if (Utils.isABDevice() && mUpdaterService != null) {
                mUpdaterService.getUpdaterController().setPerformanceMode(abPerfMode.isChecked());
            }
            if (Utils.isRecoveryUpdateExecPresent()) {
                SystemProperties.set(Constants.UPDATE_RECOVERY_PROPERTY,
                        String.valueOf(updateRecovery.isChecked()));
            }
        });
        view.findViewById(R.id.preferences_done).setOnClickListener(v -> sheet.dismiss());
        sheet.show();
    }
}
