/*
 * Copyright (C) 2017-2022 The LineageOS Project
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
package org.evolution.updater.download;

import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HttpURLConnectionClient implements DownloadClient {

    private final static String TAG = "HttpURLConnectionClient";

    // So luong ket noi song song toi da (kieu IDM)
    private static final int MAX_SEGMENTS = 7;
    // File nho hon nguong nay dung 1 connection
    private static final long MIN_SIZE_FOR_MULTI = 2L * 1024L * 1024L;
    // Buffer doc/ghi mang va merge
    private static final int IO_BUFFER_SIZE = 256 * 1024;
    // Retry ngan khi segment loi mang/tam thoi (tong lan thu = 1 + retry)
    private static final int SEGMENT_MAX_RETRIES = 1;
    private static final String USER_AGENT = "EvoXUpdater/1.0";
    private static final String PART_SUFFIX = ".part";
    private static final String MERGING_SUFFIX = ".merging";

    private final String mUrl;
    private final File mDestination;
    private final DownloadClient.ProgressListener mProgressListener;
    private final DownloadClient.DownloadCallback mCallback;
    private final boolean mUseDuplicateLinks;

    private DownloadThread mDownloadThread;

    private static class CachedHeaders implements DownloadClient.Headers {
        private final Map<String, List<String>> mHeaders;
        private final String mContentLengthOverride;

        CachedHeaders(Map<String, List<String>> headers, String contentLengthOverride) {
            mHeaders = headers;
            mContentLengthOverride = contentLengthOverride;
        }

        @Override
        public String get(String name) {
            if (mContentLengthOverride != null && "Content-Length".equalsIgnoreCase(name)) {
                return mContentLengthOverride;
            }
            if (mHeaders == null) {
                return null;
            }
            for (Map.Entry<String, List<String>> entry : mHeaders.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)
                        && entry.getValue() != null && !entry.getValue().isEmpty()) {
                    return entry.getValue().get(0);
                }
            }
            return null;
        }
    }

    HttpURLConnectionClient(String url, File destination,
            DownloadClient.ProgressListener progressListener,
            DownloadClient.DownloadCallback callback,
            boolean useDuplicateLinks) {
        mUrl = url;
        mDestination = destination;
        mProgressListener = progressListener;
        mCallback = callback;
        mUseDuplicateLinks = useDuplicateLinks;
    }

    @Override
    public void start() {
        if (mDownloadThread != null) {
            Log.e(TAG, "Already downloading");
            return;
        }
        mDownloadThread = new DownloadThread(false);
        mDownloadThread.start();
    }

    @Override
    public void resume() {
        if (mDownloadThread != null) {
            Log.e(TAG, "Already downloading");
            return;
        }
        if (!mDestination.exists() && !hasPartFiles(mDestination)) {
            mCallback.onFailure(false);
            return;
        }
        mDownloadThread = new DownloadThread(true);
        mDownloadThread.start();
    }

    @Override
    public void cancel() {
        if (mDownloadThread == null) {
            Log.e(TAG, "Not downloading");
            return;
        }
        mDownloadThread.cancelDownload();
        mDownloadThread = null;
    }

    /** Xoa file tam multi-segment (.partN, .merging) — quet sibling theo prefix. */
    public static void cleanupPartFiles(File destination) {
        if (destination == null) {
            return;
        }
        File parent = destination.getParentFile();
        String partPrefix = destination.getName() + PART_SUFFIX;
        if (parent != null) {
            File[] siblings = parent.listFiles();
            if (siblings != null) {
                for (File sibling : siblings) {
                    String name = sibling.getName();
                    if (!name.startsWith(partPrefix)) {
                        continue;
                    }
                    String idx = name.substring(partPrefix.length());
                    if (idx.isEmpty() || !isAllDigits(idx)) {
                        continue;
                    }
                    if (!sibling.delete()) {
                        Log.w(TAG, "Could not delete part " + sibling.getAbsolutePath());
                    }
                }
            }
        }
        File merging = new File(destination.getAbsolutePath() + MERGING_SUFFIX);
        if (merging.exists() && !merging.delete()) {
            Log.w(TAG, "Could not delete " + merging.getAbsolutePath());
        }
    }

    /** Con doan .part de resume multi-segment (khong phu thuoc MAX_SEGMENTS). */
    public static boolean hasPartFiles(File destination) {
        return countExistingParts(destination) > 0;
    }

    /** So part hien co = max index + 1; 0 neu khong co. */
    private static int countExistingParts(File destination) {
        if (destination == null) {
            return 0;
        }
        File parent = destination.getParentFile();
        if (parent == null) {
            return 0;
        }
        String partPrefix = destination.getName() + PART_SUFFIX;
        File[] siblings = parent.listFiles();
        if (siblings == null) {
            return 0;
        }
        int maxIdx = -1;
        for (File sibling : siblings) {
            String name = sibling.getName();
            if (!name.startsWith(partPrefix)) {
                continue;
            }
            String idx = name.substring(partPrefix.length());
            if (idx.isEmpty() || !isAllDigits(idx)) {
                continue;
            }
            try {
                maxIdx = Math.max(maxIdx, Integer.parseInt(idx));
            } catch (NumberFormatException ignored) {
                // bo qua ten khong hop le
            }
        }
        return maxIdx + 1;
    }

    private static boolean isAllDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static File partFile(File destination, int index) {
        return new File(destination.getAbsolutePath() + PART_SUFFIX + index);
    }

    private static boolean isSuccessCode(int statusCode) {
        return (statusCode / 100) == 2;
    }

    private static boolean isRedirectCode(int statusCode) {
        return (statusCode / 100) == 3;
    }

    private static boolean isPartialContentCode(int statusCode) {
        return statusCode == 206;
    }

    private static HttpURLConnection openConnection(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("User-Agent", USER_AGENT);
        return conn;
    }

    private class DownloadThread extends Thread {

        private long mTotalBytes = 0;
        private long mTotalBytesRead = 0;

        private long mCurSampleBytes = 0;
        private long mLastMillis = 0;
        private long mSpeed = -1;
        private long mEta = -1;

        private final boolean mResume;
        private final List<Thread> mWorkers = new ArrayList<>();
        private final Object mProgressLock = new Object();
        private volatile boolean mCancelled = false;
        private volatile boolean mFailed = false;

        private DownloadThread(boolean resume) {
            mResume = resume;
        }

        void cancelDownload() {
            mCancelled = true;
            interrupt();
            synchronized (mWorkers) {
                for (Thread worker : mWorkers) {
                    worker.interrupt();
                }
            }
        }

        private void calculateSpeed(boolean justResumed) {
            final long millis = SystemClock.elapsedRealtime();
            if (justResumed) {
                mLastMillis = millis;
                mSpeed = -1;
                mCurSampleBytes = mTotalBytesRead;
                return;
            }
            final long delta = millis - mLastMillis;
            if (delta > 500) {
                final long curSpeed = ((mTotalBytesRead - mCurSampleBytes) * 1000) / delta;
                if (mSpeed == -1) {
                    mSpeed = curSpeed;
                } else {
                    mSpeed = ((mSpeed * 3) + curSpeed) / 4;
                }

                mLastMillis = millis;
                mCurSampleBytes = mTotalBytesRead;
            }
        }

        private void calculateEta() {
            if (mSpeed > 0) {
                mEta = (mTotalBytes - mTotalBytesRead) / mSpeed;
            }
        }

        private void reportProgress(boolean justResumed) {
            synchronized (mProgressLock) {
                calculateSpeed(justResumed);
                calculateEta();
                if (mProgressListener != null) {
                    mProgressListener.update(mTotalBytesRead, mTotalBytes, mSpeed, mEta);
                }
            }
        }

        private void addBytesRead(long count) {
            synchronized (mProgressLock) {
                mTotalBytesRead += count;
                calculateSpeed(false);
                calculateEta();
                if (mProgressListener != null) {
                    mProgressListener.update(mTotalBytesRead, mTotalBytes, mSpeed, mEta);
                }
            }
        }

        private void changeClientUrl(HttpURLConnection[] holder, URL newUrl, String range)
                throws IOException {
            holder[0].disconnect();
            holder[0] = (HttpURLConnection) newUrl.openConnection();
            if (range != null) {
                holder[0].setRequestProperty("Range", range);
            }
            holder[0].setRequestProperty("User-Agent", USER_AGENT);
        }

        private void handleDuplicateLinks(HttpURLConnection[] holder) throws IOException {
            HttpURLConnection client = holder[0];
            String protocol = client.getURL().getProtocol();
            String range = client.getRequestProperty("Range");

            class DuplicateLink {
                private final String mDupUrl;
                private final int mPriority;
                private DuplicateLink(String url, int priority) {
                    mDupUrl = url;
                    mPriority = priority;
                }
            }

            PriorityQueue<DuplicateLink> duplicates = null;

            for (Map.Entry<String, List<String>> entry : client.getHeaderFields().entrySet()) {
                if ("Link".equalsIgnoreCase(entry.getKey())) {
                    duplicates = new PriorityQueue<>(entry.getValue().size(),
                            Comparator.comparingInt(d -> d.mPriority));

                    String regex = "(?i)<(.+)>\\s*;\\s*rel=duplicate(?:.*pri=([0-9]+).*|.*)?";
                    Pattern pattern = Pattern.compile(regex);
                    for (String field : entry.getValue()) {
                        Matcher matcher = pattern.matcher(field);
                        if (matcher.matches()) {
                            String url = matcher.group(1);
                            String pri = matcher.group(2);
                            int priority = pri != null ? Integer.parseInt(pri) : 999999;
                            duplicates.add(new DuplicateLink(url, priority));
                            Log.d(TAG, "Adding duplicate link " + url);
                        } else {
                            Log.d(TAG, "Ignoring link " + field);
                        }
                    }
                }
            }

            String newUrl = client.getHeaderField("Location");
            for (;;) {
                try {
                    URL url = new URL(newUrl);
                    if (!url.getProtocol().equals(protocol)) {
                        throw new IOException("Protocol changes are not allowed");
                    }
                    Log.d(TAG, "Downloading from " + newUrl);
                    changeClientUrl(holder, url, range);
                    holder[0].setConnectTimeout(5000);
                    holder[0].connect();
                    if (!isSuccessCode(holder[0].getResponseCode())
                            && !isRedirectCode(holder[0].getResponseCode())) {
                        throw new IOException("Server replied with " + holder[0].getResponseCode());
                    }
                    // Tiep tuc neu van con redirect
                    if (isRedirectCode(holder[0].getResponseCode())) {
                        newUrl = holder[0].getHeaderField("Location");
                        continue;
                    }
                    return;
                } catch (IOException e) {
                    if (duplicates != null && !duplicates.isEmpty()) {
                        DuplicateLink link = duplicates.poll();
                        if (link != null) {
                            newUrl = link.mDupUrl;
                            Log.e(TAG, "Using duplicate link " + link.mDupUrl, e);
                        }
                    } else {
                        throw e;
                    }
                }
            }
        }

        /** Resolve redirect / duplicate links, tra ve connection da connect (chua doc body). */
        private HttpURLConnection connectWithRedirects(String url, String rangeHeader)
                throws IOException {
            HttpURLConnection[] holder = new HttpURLConnection[] { openConnection(url) };
            if (rangeHeader != null) {
                holder[0].setRequestProperty("Range", rangeHeader);
            }
            holder[0].setInstanceFollowRedirects(!mUseDuplicateLinks);
            holder[0].setConnectTimeout(15000);
            holder[0].setReadTimeout(30000);
            holder[0].connect();
            int responseCode = holder[0].getResponseCode();
            if (mUseDuplicateLinks && isRedirectCode(responseCode)) {
                handleDuplicateLinks(holder);
            }
            return holder[0];
        }

        /**
         * Probe Range: GET bytes=0-0. Tra ve [resolvedUrl, contentLength] neu ho tro 206;
         * null contentLength neu khong ho tro (fallback single).
         */
        private ProbeResult probeRangeSupport() throws IOException {
            HttpURLConnection conn = null;
            try {
                conn = connectWithRedirects(mUrl, "bytes=0-0");
                int code = conn.getResponseCode();
                String resolvedUrl = conn.getURL().toString();
                Map<String, List<String>> headers = conn.getHeaderFields();

                if (isPartialContentCode(code)) {
                    long total = parseContentRangeTotal(conn.getHeaderField("Content-Range"));
                    if (total <= 0) {
                        total = conn.getContentLengthLong();
                    }
                    // Doc va bo 1 byte probe
                    try (InputStream in = conn.getInputStream()) {
                        //noinspection ResultOfMethodCallIgnored
                        in.read();
                    }
                    if (total > 0) {
                        return new ProbeResult(resolvedUrl, total, true, headers);
                    }
                }

                if (isSuccessCode(code) && !isPartialContentCode(code)) {
                    // Server bo qua Range — khong ho tro multi
                    long total = conn.getContentLengthLong();
                    return new ProbeResult(resolvedUrl, total, false, headers);
                }

                throw new IOException("Probe failed with HTTP " + code);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }

        private long parseContentRangeTotal(String contentRange) {
            if (contentRange == null) {
                return -1;
            }
            // bytes start-end/total
            int slash = contentRange.lastIndexOf('/');
            if (slash < 0 || slash + 1 >= contentRange.length()) {
                return -1;
            }
            String totalStr = contentRange.substring(slash + 1).trim();
            if ("*".equals(totalStr)) {
                return -1;
            }
            try {
                return Long.parseLong(totalStr);
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        private static class ProbeResult {
            final String resolvedUrl;
            final long contentLength;
            final boolean rangeSupported;
            final Map<String, List<String>> headers;

            ProbeResult(String resolvedUrl, long contentLength, boolean rangeSupported,
                    Map<String, List<String>> headers) {
                this.resolvedUrl = resolvedUrl;
                this.contentLength = contentLength;
                this.rangeSupported = rangeSupported;
                this.headers = headers;
            }
        }

        private static class Segment {
            final int index;
            final long start;
            final long end; // inclusive
            long downloaded; // bytes da co trong .part

            Segment(int index, long start, long end, long downloaded) {
                this.index = index;
                this.start = start;
                this.end = end;
                this.downloaded = downloaded;
            }

            long remaining() {
                return (end - start + 1) - downloaded;
            }

            boolean isComplete() {
                return remaining() <= 0;
            }
        }

        private List<Segment> buildSegments(long totalBytes) {
            int count = idealSegmentCount(totalBytes);
            if (mResume) {
                int existing = countExistingParts(mDestination);
                if (existing > 0) {
                    // Giu so segment luc bat dau tai de resume dung bien
                    count = existing;
                }
            }

            List<Segment> segments = new ArrayList<>(count);
            long segSize = totalBytes / count;
            for (int i = 0; i < count; i++) {
                long start = i * segSize;
                long end = (i == count - 1) ? (totalBytes - 1) : (start + segSize - 1);
                File part = partFile(mDestination, i);
                long downloaded = 0;
                if (mResume && part.exists()) {
                    downloaded = part.length();
                    long segLen = end - start + 1;
                    if (downloaded > segLen) {
                        downloaded = segLen;
                    }
                }
                segments.add(new Segment(i, start, end, downloaded));
            }
            return segments;
        }

        private static int idealSegmentCount(long totalBytes) {
            if (totalBytes < MIN_SIZE_FOR_MULTI) {
                return 1;
            }
            // Khong tao segment nho hon ~512KB neu file vua
            long minSeg = 512L * 1024L;
            return (int) Math.max(1, Math.min(MAX_SEGMENTS, totalBytes / minSeg));
        }

        private void ensurePlaceholderDestination() throws IOException {
            if (!mDestination.exists()) {
                File parent = mDestination.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("Cannot create download dir");
                }
                if (!mDestination.createNewFile() && !mDestination.exists()) {
                    throw new IOException("Cannot create placeholder " + mDestination);
                }
            }
        }

        private void downloadMulti(ProbeResult probe) throws IOException, InterruptedException {
            List<Segment> segments = buildSegments(probe.contentLength);
            mTotalBytes = probe.contentLength;
            mTotalBytesRead = 0;
            for (Segment seg : segments) {
                mTotalBytesRead += seg.downloaded;
            }

            ensurePlaceholderDestination();
            // Placeholder giu length=0 de UpdaterController khong tu coi la xong
            if (mDestination.length() > 0 && !hasCompleteFile()) {
                try (FileOutputStream trunc = new FileOutputStream(mDestination, false)) {
                    // truncate
                }
            }

            mCallback.onResponse(new CachedHeaders(probe.headers,
                    Long.toString(probe.contentLength)));
            reportProgress(mResume && mTotalBytesRead > 0);

            if (mTotalBytesRead >= mTotalBytes) {
                mergeParts(segments);
                mCallback.onSuccess();
                return;
            }

            final String downloadUrl = probe.resolvedUrl;
            final AtomicLong errorFlag = new AtomicLong(0);
            final Object doneLock = new Object();
            final int[] remainingWorkers = new int[]{0};

            for (Segment seg : segments) {
                if (seg.isComplete()) {
                    continue;
                }
                remainingWorkers[0]++;
                Thread worker = new Thread(() -> {
                    try {
                        downloadSegmentWithRetry(downloadUrl, seg);
                    } catch (IOException e) {
                        if (!mCancelled) {
                            Log.e(TAG, "Segment " + seg.index + " failed", e);
                            errorFlag.set(1);
                            mFailed = true;
                            interruptWorkers();
                        }
                    } finally {
                        synchronized (doneLock) {
                            remainingWorkers[0]--;
                            doneLock.notifyAll();
                        }
                    }
                }, "UpdaterSeg-" + seg.index);
                synchronized (mWorkers) {
                    mWorkers.add(worker);
                }
                worker.start();
            }

            synchronized (doneLock) {
                while (remainingWorkers[0] > 0 && !mCancelled && errorFlag.get() == 0) {
                    doneLock.wait(1000);
                }
            }

            if (mCancelled || isInterrupted()) {
                interruptWorkers();
                joinWorkers();
                mCallback.onFailure(true);
                return;
            }
            if (errorFlag.get() != 0 || mFailed) {
                interruptWorkers();
                joinWorkers();
                mCallback.onFailure(false);
                return;
            }

            // Doi worker ket thuc sach
            joinWorkers();

            for (Segment seg : segments) {
                File part = partFile(mDestination, seg.index);
                long expected = seg.end - seg.start + 1;
                if (!part.exists() || part.length() != expected) {
                    Log.e(TAG, "Segment " + seg.index + " incomplete after download");
                    mCallback.onFailure(false);
                    return;
                }
            }

            mergeParts(segments);
            reportProgress(false);
            mCallback.onSuccess();
        }

        private boolean hasCompleteFile() {
            return mDestination.exists() && mTotalBytes > 0
                    && mDestination.length() >= mTotalBytes && !hasPartFiles(mDestination);
        }

        private void interruptWorkers() {
            synchronized (mWorkers) {
                for (Thread worker : mWorkers) {
                    worker.interrupt();
                }
            }
        }

        private void joinWorkers() {
            synchronized (mWorkers) {
                for (Thread worker : mWorkers) {
                    try {
                        worker.join(5000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        private void downloadSegmentWithRetry(String url, Segment seg) throws IOException {
            IOException last = null;
            for (int attempt = 0; attempt <= SEGMENT_MAX_RETRIES; attempt++) {
                if (mCancelled || Thread.currentThread().isInterrupted()) {
                    return;
                }
                try {
                    downloadSegment(url, seg);
                    return;
                } catch (IOException e) {
                    last = e;
                    if (mCancelled || Thread.currentThread().isInterrupted()
                            || attempt >= SEGMENT_MAX_RETRIES) {
                        throw e;
                    }
                    Log.w(TAG, "Segment " + seg.index + " retry " + (attempt + 1)
                            + "/" + SEGMENT_MAX_RETRIES, e);
                    try {
                        Thread.sleep(400L * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            if (last != null) {
                throw last;
            }
        }

        private void downloadSegment(String url, Segment seg) throws IOException {
            long absStart = seg.start + seg.downloaded;
            if (absStart > seg.end) {
                return;
            }
            String range = "bytes=" + absStart + "-" + seg.end;
            HttpURLConnection conn = null;
            try {
                // URL da resolve tu probe; moi segment van can Range rieng
                conn = connectWithRedirects(url, range);
                int code = conn.getResponseCode();
                boolean wholeFile = seg.start == 0 && seg.end == mTotalBytes - 1;
                if (!isPartialContentCode(code)
                        && !(wholeFile && absStart == 0 && isSuccessCode(code))) {
                    throw new IOException("Segment " + seg.index + " HTTP " + code);
                }
                File part = partFile(mDestination, seg.index);
                try (
                        InputStream inputStream = conn.getInputStream();
                        RandomAccessFile raf = new RandomAccessFile(part, "rw")
                ) {
                    raf.seek(seg.downloaded);
                    byte[] buf = new byte[IO_BUFFER_SIZE];
                    int count;
                    while (!mCancelled && !Thread.currentThread().isInterrupted()
                            && (count = inputStream.read(buf)) > 0) {
                        raf.write(buf, 0, count);
                        seg.downloaded += count;
                        addBytesRead(count);
                    }
                    if (mCancelled || Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    if (!seg.isComplete()) {
                        throw new IOException("Segment " + seg.index + " ended early");
                    }
                }
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }

        private void mergeParts(List<Segment> segments) throws IOException {
            File temp = new File(mDestination.getAbsolutePath() + MERGING_SUFFIX);
            try (FileOutputStream out = new FileOutputStream(temp, false)) {
                byte[] buf = new byte[IO_BUFFER_SIZE];
                for (Segment seg : segments) {
                    File part = partFile(mDestination, seg.index);
                    try (FileInputStream in = new FileInputStream(part)) {
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                        }
                    }
                }
                out.flush();
            }
            if (temp.length() != mTotalBytes) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                throw new IOException("Merged size mismatch: " + temp.length()
                        + " != " + mTotalBytes);
            }
            if (mDestination.exists() && !mDestination.delete()) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                throw new IOException("Cannot replace destination");
            }
            if (!temp.renameTo(mDestination)) {
                // Fallback copy neu rename that bai
                try (FileInputStream in = new FileInputStream(temp);
                     FileOutputStream out = new FileOutputStream(mDestination)) {
                    byte[] buf = new byte[IO_BUFFER_SIZE];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                }
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
            if (mDestination.length() != mTotalBytes) {
                throw new IOException("Final size mismatch: " + mDestination.length()
                        + " != " + mTotalBytes);
            }
            cleanupPartFiles(mDestination);
        }

        private void downloadSingle(boolean resume, String urlOrNull, Map<String, List<String>> headers)
                throws IOException {
            // Resume single: Range tu length file dich (khong dung .part)
            cleanupPartFiles(mDestination);

            String url = urlOrNull != null ? urlOrNull : mUrl;
            String range = null;
            long existing = 0;
            if (resume && mDestination.exists()) {
                existing = mDestination.length();
                if (existing > 0) {
                    range = "bytes=" + existing + "-";
                }
            }

            HttpURLConnection conn = null;
            try {
                conn = connectWithRedirects(url, range);
                int responseCode = conn.getResponseCode();

                boolean justResumed = false;
                if (resume && isPartialContentCode(responseCode)) {
                    justResumed = true;
                    mTotalBytesRead = existing;
                    Log.d(TAG, "The server fulfilled the partial content request");
                } else if (resume && existing > 0) {
                    Log.e(TAG, "The server replied with code " + responseCode);
                    mCallback.onFailure(isInterrupted() || mCancelled);
                    return;
                } else if (!isSuccessCode(responseCode)) {
                    Log.e(TAG, "The server replied with code " + responseCode);
                    mCallback.onFailure(isInterrupted() || mCancelled);
                    return;
                }

                Map<String, List<String>> respHeaders =
                        headers != null ? headers : conn.getHeaderFields();
                long bodyLength = conn.getContentLengthLong();
                mTotalBytes = bodyLength + mTotalBytesRead;
                String lengthForCallback = mTotalBytes > 0 ? Long.toString(mTotalBytes) : null;
                mCallback.onResponse(new CachedHeaders(respHeaders, lengthForCallback));

                try (
                        InputStream inputStream = conn.getInputStream();
                        OutputStream outputStream = new FileOutputStream(mDestination, justResumed)
                ) {
                    byte[] b = new byte[IO_BUFFER_SIZE];
                    int count;
                    reportProgress(justResumed);
                    justResumed = false;
                    while (!mCancelled && !isInterrupted() && (count = inputStream.read(b)) > 0) {
                        outputStream.write(b, 0, count);
                        mTotalBytesRead += count;
                        reportProgress(false);
                    }
                    reportProgress(false);
                    outputStream.flush();

                    if (mCancelled || isInterrupted()) {
                        mCallback.onFailure(true);
                    } else {
                        mCallback.onSuccess();
                    }
                }
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }

        @Override
        public void run() {
            try {
                // Resume co .part → multi; resume chi file dich co data → single;
                // placeholder length=0 khong bi coi la xong
                boolean preferSingleResume = mResume && mDestination.exists()
                        && mDestination.length() > 0 && !hasPartFiles(mDestination);
                if (preferSingleResume) {
                    downloadSingle(true, null, null);
                    return;
                }

                ProbeResult probe = probeRangeSupport();
                boolean useMulti = probe.rangeSupported
                        && probe.contentLength >= MIN_SIZE_FOR_MULTI;

                if (useMulti) {
                    Log.d(TAG, "Multi-segment download, size=" + probe.contentLength
                            + " maxSegments=" + MAX_SEGMENTS);
                    downloadMulti(probe);
                } else {
                    Log.d(TAG, "Single-connection download (rangeSupported="
                            + probe.rangeSupported + ", size=" + probe.contentLength + ")");
                    if (mResume && hasPartFiles(mDestination)) {
                        // Co part nhung server khong con ho tro Range
                        Log.e(TAG, "Cannot resume multi-segment without Range support");
                        mCallback.onFailure(false);
                        return;
                    }
                    downloadSingle(mResume, probe.resolvedUrl, probe.headers);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                mCallback.onFailure(true);
            } catch (IOException e) {
                Log.e(TAG, "Error downloading file", e);
                mCallback.onFailure(mCancelled || isInterrupted());
            }
        }
    }
}
