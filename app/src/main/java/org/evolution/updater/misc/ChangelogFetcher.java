/*
 * Copyright (C) 2026 The Evolution X Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.evolution.updater.misc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Tai changelog text tu URL (co follow redirect). */
public final class ChangelogFetcher {

    private ChangelogFetcher() {
    }

    public static String fetch(String urlString) throws IOException {
        URL url = new URL(urlString);
        for (int hop = 0; hop < 10; hop++) {
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "EvoXUpdater/1.0");
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.isEmpty()) {
                    throw new IOException("Redirect without Location: " + code);
                }
                url = new URL(url, location);
                continue;
            }
            if (code / 100 != 2) {
                conn.disconnect();
                throw new IOException("HTTP " + code);
            }
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                int total = 0;
                final int maxBytes = 512 * 1024;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > maxBytes) {
                        throw new IOException("Changelog too large");
                    }
                    out.write(buf, 0, n);
                }
                return out.toString(StandardCharsets.UTF_8.name());
            } finally {
                conn.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }
}
