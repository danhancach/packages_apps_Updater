/*
 * Copyright (C) 2017 The LineageOS Project
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
package org.evolution.updater.misc;

import android.content.Context;
import android.os.Build;
import android.os.SystemProperties;

import org.evolution.updater.R;

import java.util.Locale;

public final class BuildInfoUtils {

    private BuildInfoUtils() {
    }

    public static long getBuildDateTimestamp() {
        return SystemProperties.getLong(Constants.PROP_BUILD_DATE, 0);
    }

    public static String getBuildVersion() {
        return SystemProperties.get(Constants.PROP_BUILD_VERSION);
    }

    /** Dong thong tin ROM: Evolution X  12.2 | A17 | UNOFFICIAL | PDX237 */
    public static String getRomInfoLine(Context context) {
        String modVersion = getBuildVersion();
        if (modVersion == null || modVersion.isEmpty()) {
            modVersion = "?";
        }

        String evoAndroid = SystemProperties.get(Constants.PROP_EVOLUTION_VERSION, "");
        if (evoAndroid.isEmpty()) {
            evoAndroid = Build.VERSION.RELEASE != null ? Build.VERSION.RELEASE : "";
        }
        String androidLabel = formatAndroidMajor(evoAndroid);

        String buildType = SystemProperties.get(Constants.PROP_EVOLUTION_BUILD_TYPE, "Unofficial");
        if (buildType.isEmpty()) {
            buildType = "Unofficial";
        }
        buildType = buildType.toUpperCase(Locale.US);

        String device = SystemProperties.get(Constants.PROP_DEVICE,
                SystemProperties.get(Constants.PROP_NEXT_DEVICE, Build.DEVICE));
        if (device == null || device.isEmpty()) {
            device = "?";
        }
        device = device.toUpperCase(Locale.US);

        return context.getString(R.string.changelog_rom_info_format,
                modVersion, androidLabel, buildType, device);
    }

    private static String formatAndroidMajor(String version) {
        if (version == null || version.isEmpty()) {
            return "A?";
        }
        String major = version;
        int dot = version.indexOf('.');
        if (dot > 0) {
            major = version.substring(0, dot);
        }
        if (major.regionMatches(true, 0, "A", 0, 1) && major.length() > 1) {
            return "A" + major.substring(1);
        }
        return "A" + major;
    }
}
