/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.kimjio.tinyplanet;

import android.content.ContentResolver;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.net.Uri;
import android.util.Log;

import com.kimjio.tinyplanet.app.MediaSaver;
import com.kimjio.tinyplanet.exif.ExifInterface;

import java.io.IOException;

/** A class implementing {@link MediaSaver}. */
public class MediaSaverImpl implements MediaSaver {
    private static final String TAG = "MediaSaverImpl";

    /** The memory limit for unsaved image is 30MB. */
    // TODO: Revert this back to 20 MB when CaptureSession API supports saving
    // bursts.
    private static final int SAVE_TASK_MEMORY_LIMIT = 30 * 1024 * 1024;

    private final ContentResolver mContentResolver;

    /** Memory used by the total queued save request, in bytes. */
    private long mMemoryUse;

    private QueueListener mQueueListener;

    /**
     * @param contentResolver The {@link ContentResolver} to be updated.
     */
    public MediaSaverImpl(ContentResolver contentResolver) {
        mContentResolver = contentResolver;
        mMemoryUse = 0;
    }

    @Override
    public boolean isQueueFull() {
        return (mMemoryUse >= SAVE_TASK_MEMORY_LIMIT);
    }

    @Override
    public void addImage(
            final byte[] data,
            String title,
            long date,
            Location loc,
            int width,
            int height,
            int orientation,
            ExifInterface exif,
            OnMediaSavedListener l) {
        addImage(data, title, date, loc, width, height, orientation, exif, l, MIME_TYPE_JPEG);
    }

    @Override
    public void addImage(
            final byte[] data,
            String title,
            long date,
            Location loc,
            int width,
            int height,
            int orientation,
            ExifInterface exif,
            OnMediaSavedListener l,
            String mimeType) {
        if (isQueueFull()) {
            Log.e(TAG, "Cannot add image when the queue is full");
            return;
        }
        ImageSaveTask t =
                new ImageSaveTask(
                        data,
                        title,
                        date,
                        (loc == null) ? null : new Location(loc),
                        width,
                        height,
                        orientation,
                        mimeType,
                        exif,
                        mContentResolver,
                        l);

        mMemoryUse += data.length;
        if (isQueueFull()) {
            onQueueFull();
        }
        t.execute();
    }

    @Override
    public void addImage(
            final byte[] data,
            String title,
            long date,
            Location loc,
            int orientation,
            ExifInterface exif,
            OnMediaSavedListener l) {
        // When dimensions are unknown, pass 0 as width and height,
        // and decode image for width and height later in a background thread
        addImage(data, title, date, loc, 0, 0, orientation, exif, l, MIME_TYPE_JPEG);
    }

    @Override
    public void addImage(
            final byte[] data,
            String title,
            Location loc,
            int width,
            int height,
            int orientation,
            ExifInterface exif,
            OnMediaSavedListener l) {
        addImage(
                data,
                title,
                System.currentTimeMillis(),
                loc,
                width,
                height,
                orientation,
                exif,
                l,
                MIME_TYPE_JPEG);
    }

    @Override
    public void setQueueListener(QueueListener l) {
        mQueueListener = l;
        if (l == null) {
            return;
        }
        l.onQueueStatus(isQueueFull());
    }

    private void onQueueFull() {
        if (mQueueListener != null) {
            mQueueListener.onQueueStatus(true);
        }
    }

    private void onQueueAvailable() {
        if (mQueueListener != null) {
            mQueueListener.onQueueStatus(false);
        }
    }

    private class ImageSaveTask implements IAsyncTask<Void, Uri> {
        private final byte[] data;
        private final String title;
        private final long date;
        private final Location loc;
        private int width, height;
        private final int orientation;
        private final String mimeType;
        private final ExifInterface exif;
        private final ContentResolver resolver;
        private final OnMediaSavedListener listener;

        public ImageSaveTask(
                byte[] data,
                String title,
                long date,
                Location loc,
                int width,
                int height,
                int orientation,
                String mimeType,
                ExifInterface exif,
                ContentResolver resolver,
                OnMediaSavedListener listener) {
            this.data = data;
            this.title = title;
            this.date = date;
            this.loc = loc;
            this.width = width;
            this.height = height;
            this.orientation = orientation;
            this.mimeType = mimeType;
            this.exif = exif;
            this.resolver = resolver;
            this.listener = listener;
        }

        @Override
        public Uri doInBackground(Void... v) {
            if (width == 0 || height == 0) {
                // Decode bounds
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, options);
                width = options.outWidth;
                height = options.outHeight;
            }
            try {
                return Storage.instance()
                        .addImage(
                                resolver,
                                title,
                                date,
                                loc,
                                orientation,
                                exif,
                                data,
                                width,
                                height,
                                mimeType);
            } catch (IOException e) {
                Log.e(TAG, "Failed to write data", e);
                return null;
            }
        }

        @Override
        public void onPostExecute(Uri uri) {
            if (listener != null) {
                listener.onMediaSaved(uri);
            }
            boolean previouslyFull = isQueueFull();
            mMemoryUse -= data.length;
            if (isQueueFull() != previouslyFull) {
                onQueueAvailable();
            }
        }
    }
}
