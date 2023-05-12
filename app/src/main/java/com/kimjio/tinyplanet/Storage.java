/*
 * Copyright (C) 2010 The Android Open Source Project
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
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore.Images.Media;
import android.util.Log;

import com.kimjio.tinyplanet.exif.ExifInterface;
import com.kimjio.tinyplanet.util.AndroidContext;

import java.io.IOException;
import java.io.OutputStream;

public class Storage {
    public final String DIRECTORY;
    public static final String JPEG_POSTFIX = ".jpg";
    private static final String TAG = "Storage";

    private static class Singleton {
        private static final Storage INSTANCE = new Storage(AndroidContext.instance().get());
    }

    public static Storage instance() {
        return Singleton.INSTANCE;
    }

    private Storage(Context context) {
        DIRECTORY = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES).getPath();
    }

    /**
     * Saves the media with a given MIME type and adds it to the MediaStore.
     *
     * <p>The path will be automatically generated according to the title.
     *
     * @param resolver The The content resolver to use.
     * @param title The title of the media file.
     * @param data The data to save.
     * @param date The date for the media file.
     * @param location The location of the media file.
     * @param orientation The orientation of the media file.
     * @param exif The EXIF info. Can be {@code null}.
     * @param width The width of the media file after the orientation is applied.
     * @param height The height of the media file after the orientation is applied.
     * @param mimeType The MIME type of the data.
     * @return The URI of the added image, or null if the image could not be added.
     */
    public Uri addImage(
            ContentResolver resolver,
            String title,
            long date,
            Location location,
            int orientation,
            ExifInterface exif,
            byte[] data,
            int width,
            int height,
            String mimeType)
            throws IOException {

        if (data.length > 0) {
            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
            return addImageToMediaStore(
                    resolver,
                    title,
                    date,
                    location,
                    orientation,
                    data.length,
                    bitmap,
                    width,
                    height,
                    mimeType,
                    exif);
        }
        return null;
    }

    /**
     * Add the entry for the media file to media store.
     *
     * @param resolver The The content resolver to use.
     * @param title The title of the media file.
     * @param date The date for the media file.
     * @param location The location of the media file.
     * @param orientation The orientation of the media file.
     * @param bitmap The bitmap representation of the media to store.
     * @param width The width of the media file after the orientation is applied.
     * @param height The height of the media file after the orientation is applied.
     * @param mimeType The MIME type of the data.
     * @param exif The exif of the image.
     * @return The content URI of the inserted media file or null, if the image could not be added.
     */
    public Uri addImageToMediaStore(
            ContentResolver resolver,
            String title,
            long date,
            Location location,
            int orientation,
            long jpegLength,
            Bitmap bitmap,
            int width,
            int height,
            String mimeType,
            ExifInterface exif) {
        // Insert into MediaStore.
        ContentValues values = getContentValuesForData(title, date, location, mimeType, true);

        Uri uri = null;
        try {
            uri = resolver.insert(Media.EXTERNAL_CONTENT_URI, values);
            writeBitmap(uri, exif, bitmap, resolver);
        } catch (Throwable th) {
            // This can happen when the external volume is already mounted, but
            // MediaScanner has not notify MediaProvider to add that volume.
            // The picture is still safe and MediaScanner will find it and
            // insert it into MediaProvider. The only problem is that the user
            // cannot click the thumbnail to review the picture.
            Log.e(TAG, "Failed to write MediaStore" + th);
            if (uri != null) {
                resolver.delete(uri, null, null);
            }
        }
        return uri;
    }

    private void writeBitmap(Uri uri, ExifInterface exif, Bitmap bitmap, ContentResolver resolver)
            throws IOException {
        OutputStream os = resolver.openOutputStream(uri);
        if (exif != null) {
            exif.writeExif(bitmap, os);
        } else {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, os);
        }
        os.close();

        ContentValues publishValues = new ContentValues();
        publishValues.put(Media.IS_PENDING, 0);
        resolver.update(uri, publishValues, null, null);
        Log.i(TAG, "Image with uri: " + uri + " was published to the MediaStore");
    }

    // Get a ContentValues object for the given photo data
    public ContentValues getContentValuesForData(
            String title, long date, Location location, String mimeType, boolean isPending) {

        ContentValues values = new ContentValues(11);
        values.put(Media.TITLE, title);
        values.put(Media.DISPLAY_NAME, title + JPEG_POSTFIX);
        values.put(Media.DATE_TAKEN, date);
        values.put(Media.MIME_TYPE, mimeType);

        if (isPending) {
            values.put(Media.IS_PENDING, 1);
        } else {
            values.put(Media.IS_PENDING, 0);
        }

        if (location != null) {
            values.put(Media.LATITUDE, location.getLatitude());
            values.put(Media.LONGITUDE, location.getLongitude());
        }
        return values;
    }
}
