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

import android.app.ProgressDialog;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;

import androidx.fragment.app.DialogFragment;

import com.adobe.internal.xmp.XMPException;
import com.adobe.internal.xmp.XMPMeta;
import com.google.android.material.slider.Slider;
import com.kimjio.tinyplanet.TinyPlanetPreview.PreviewSizeListener;
import com.kimjio.tinyplanet.app.MediaSaver;
import com.kimjio.tinyplanet.app.MediaSaver.OnMediaSavedListener;
import com.kimjio.tinyplanet.exif.ExifInterface;
import com.kimjio.tinyplanet.util.XmpUtil;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An activity that provides an editor UI to create a TinyPlanet image from a 360 degree
 * stereographically mapped panoramic image.
 */
public class TinyPlanetFragment extends DialogFragment implements PreviewSizeListener {
    /** Argument to tell the fragment the URI of the original panoramic image. */
    public static final String ARGUMENT_URI = "uri";
    /** Argument to tell the fragment the title of the original panoramic image. */
    public static final String ARGUMENT_TITLE = "title";

    public static final String CROPPED_AREA_IMAGE_WIDTH_PIXELS = "CroppedAreaImageWidthPixels";
    public static final String CROPPED_AREA_IMAGE_HEIGHT_PIXELS = "CroppedAreaImageHeightPixels";
    public static final String CROPPED_AREA_FULL_PANO_WIDTH_PIXELS = "FullPanoWidthPixels";
    public static final String CROPPED_AREA_FULL_PANO_HEIGHT_PIXELS = "FullPanoHeightPixels";
    public static final String CROPPED_AREA_LEFT = "CroppedAreaLeftPixels";
    public static final String CROPPED_AREA_TOP = "CroppedAreaTopPixels";
    public static final String GOOGLE_PANO_NAMESPACE = "http://ns.google.com/photos/1.0/panorama/";

    private static final String TAG = "TinyPlanetActivity";
    /** Delay between a value update and the renderer running. */
    private static final int RENDER_DELAY_MILLIS = 50;
    /** Filename prefix to prepend to the original name for the new file. */
    private static final String FILENAME_PREFIX = "TINYPLANET_";

    private Uri mSourceImageUri;
    private TinyPlanetPreview mPreview;
    private int mPreviewSizePx = 0;
    private float mCurrentZoom = 0.5f;
    private float mCurrentAngle = 0;
    private ProgressDialog mDialog;

    /** Lock for the result preview bitmap. We can't change it while we're trying to draw it. */
    private final Lock mResultLock = new ReentrantLock();

    /** The title of the original panoramic image. */
    private String mOriginalTitle = "";

    /** The padded source bitmap. */
    private Bitmap mSourceBitmap;
    /** The resulting preview bitmap. */
    private Bitmap mResultBitmap;

    /** Used to delay-post a tiny planet rendering task. */
    private final Handler mHandler = new Handler();

    private final Object mRenderingLock = new Object();
    /** Whether rendering is in progress right now. */
    private boolean mRendering = false;
    /**
     * Whether we should render one more time after the current rendering run is done. This is
     * needed when there was an update to the values during the current rendering.
     */
    private boolean mRenderOneMore = false;

    /** Tiny planet data plus size. */
    private static final class TinyPlanetImage {
        public final byte[] mJpegData;
        public final int mSize;

        public TinyPlanetImage(byte[] jpegData, int size) {
            mJpegData = jpegData;
            mSize = size;
        }
    }

    /** Creates and executes a task to create a tiny planet with the current values. */
    private final Runnable mCreateTinyPlanetRunnable =
            new Runnable() {
                @Override
                public void run() {
                    synchronized (mRenderingLock) {
                        if (mRendering) {
                            mRenderOneMore = true;
                            return;
                        }
                        mRendering = true;
                    }

                    new IAsyncTask<Void, Void>() {
                        @Override
                        public Void doInBackground(Void... params) {
                            mResultLock.lock();
                            try {
                                if (mSourceBitmap == null || mResultBitmap == null) {
                                    return null;
                                }
                                int width = mSourceBitmap.getWidth();
                                int height = mSourceBitmap.getHeight();
                                TinyPlanetNative.process(
                                        mSourceBitmap,
                                        width,
                                        height,
                                        mResultBitmap,
                                        mPreviewSizePx,
                                        mCurrentZoom,
                                        mCurrentAngle);
                            } finally {
                                mResultLock.unlock();
                            }
                            return null;
                        }

                        @Override
                        public void onPostExecute(Void result) {
                            mPreview.setBitmap(mResultBitmap, mResultLock);
                            synchronized (mRenderingLock) {
                                mRendering = false;
                                if (mRenderOneMore) {
                                    mRenderOneMore = false;
                                    scheduleUpdate();
                                }
                            }
                        }
                    }.execute();
                }
            };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(DialogFragment.STYLE_NORMAL, R.style.Theme_MakeTinyPlanet);
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        getDialog().getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        getDialog().setCanceledOnTouchOutside(true);

        View view = inflater.inflate(R.layout.tinyplanet_editor, container, false);
        mPreview = view.findViewById(R.id.preview);
        mPreview.setPreviewSizeChangeListener(this);

        // Zoom slider setup.
        Slider zoomSlider = view.findViewById(R.id.zoomSlider);
        zoomSlider.addOnChangeListener(
                (Slider slider, float progress, boolean fromUser) -> {
                    onZoomChange((int) progress);
                });

        // Rotation slider setup.
        Slider angleSlider = view.findViewById(R.id.angleSlider);
        angleSlider.addOnChangeListener(
                (Slider seekBar, float progress, boolean fromUser) -> {
                    onAngleChange((int) progress);
                });

        Button createButton = view.findViewById(R.id.creatTinyPlanetButton);
        createButton.setOnClickListener(
                v -> onCreateTinyPlanet());

        mOriginalTitle = getArguments().getString(ARGUMENT_TITLE);
        mSourceImageUri = Uri.parse(getArguments().getString(ARGUMENT_URI));
        mSourceBitmap = createPaddedSourceImage(mSourceImageUri, true);

        if (mSourceBitmap == null) {
            Log.e(TAG, "Could not decode source image.");
            dismiss();
        }
        return view;
    }

    /**
     * From the given URI this method creates a 360/180 padded image that is ready to be made a tiny
     * planet.
     */
    private Bitmap createPaddedSourceImage(Uri sourceImageUri, boolean previewSize) {
        InputStream is = getInputStream(sourceImageUri);
        if (is == null) {
            Log.e(TAG, "Could not create input stream for image.");
            dismiss();
        }
        Bitmap sourceBitmap = BitmapFactory.decodeStream(is);

        is = getInputStream(sourceImageUri);
        XMPMeta xmp = XmpUtil.extractXMPMeta(is);

        if (xmp != null) {
            int size = previewSize ? getDisplaySize() : sourceBitmap.getWidth();
            sourceBitmap = createPaddedBitmap(sourceBitmap, xmp, size);
        }
        return sourceBitmap;
    }

    /**
     * Starts an asynchronous task to create a tiny planet. Once done, will add the new image to the
     * filmstrip and dismisses the fragment.
     */
    private void onCreateTinyPlanet() {
        // Make sure we stop rendering before we create the high-res tiny
        // planet.
        synchronized (mRenderingLock) {
            mRenderOneMore = false;
        }

        final String savingTinyPlanet =
                requireActivity().getResources().getString(R.string.saving_tiny_planet);

        mDialog = ProgressDialog.show(getActivity(), null, savingTinyPlanet, true, false);
        new IAsyncTask<Void, TinyPlanetImage>() {
            @Override
            public TinyPlanetImage doInBackground(Void... params) {
                return createFinalTinyPlanet();
            }

            @Override
            public void onPostExecute(TinyPlanetImage image) {
                // Once created, store the new file.
                MediaSaver mediaSaver = MediaSaver.getInstance(requireContext());
                OnMediaSavedListener doneListener =
                        uri -> {
                            mDialog.dismiss();
                            TinyPlanetFragment.this.dismiss();
                        };
                String tinyPlanetTitle = FILENAME_PREFIX + mOriginalTitle;
                mediaSaver.addImage(
                        image.mJpegData,
                        tinyPlanetTitle,
                        (new Date()).getTime(),
                        null,
                        image.mSize,
                        image.mSize,
                        0,
                        null,
                        doneListener);
            }
        }.execute();
    }

    /**
     * Creates the high quality tiny planet file and adds it to the media service. Don't call this
     * on the UI thread.
     */
    private TinyPlanetImage createFinalTinyPlanet() {
        // Free some memory we don't need anymore as we're going to dimiss the
        // fragment after the tiny planet creation.
        mResultLock.lock();
        try {
            mResultBitmap.recycle();
            mResultBitmap = null;
            mSourceBitmap.recycle();
            mSourceBitmap = null;
        } finally {
            mResultLock.unlock();
        }

        // Create a high-resolution padded image.
        Bitmap sourceBitmap = createPaddedSourceImage(mSourceImageUri, false);
        int width = sourceBitmap.getWidth();
        int height = sourceBitmap.getHeight();

        int outputSize = width / 2;
        Bitmap resultBitmap = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888);

        TinyPlanetNative.process(
                sourceBitmap, width, height, resultBitmap, outputSize, mCurrentZoom, mCurrentAngle);

        // Free the sourceImage memory as we don't need it and we need memory
        // for the JPEG bytes.
        sourceBitmap.recycle();
        sourceBitmap = null;

        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        resultBitmap.compress(CompressFormat.JPEG, 100, jpeg);
        return new TinyPlanetImage(addExif(jpeg.toByteArray()), outputSize);
    }

    /**
     * Adds basic EXIF data to the tiny planet image so it an be rewritten later.
     *
     * @param jpeg the JPEG data of the tiny planet.
     * @return The JPEG data containing basic EXIF.
     */
    private byte[] addExif(byte[] jpeg) {
        ExifInterface exif = new ExifInterface();
        exif.addDateTimeStampTag(
                ExifInterface.TAG_DATE_TIME, System.currentTimeMillis(), TimeZone.getDefault());
        ByteArrayOutputStream jpegOut = new ByteArrayOutputStream();
        try {
            exif.writeExif(jpeg, jpegOut);
        } catch (IOException e) {
            Log.e(TAG, "Could not write EXIF", e);
        }
        return jpegOut.toByteArray();
    }

    private int getDisplaySize() {
        Display display = getActivity().getWindowManager().getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        return Math.min(size.x, size.y);
    }

    @Override
    public void onSizeChanged(int sizePx) {
        mPreviewSizePx = sizePx;
        mResultLock.lock();
        try {
            if (mResultBitmap == null
                    || mResultBitmap.getWidth() != sizePx
                    || mResultBitmap.getHeight() != sizePx) {
                if (mResultBitmap != null) {
                    mResultBitmap.recycle();
                }
                mResultBitmap =
                        Bitmap.createBitmap(
                                mPreviewSizePx, mPreviewSizePx, Bitmap.Config.ARGB_8888);
            }
        } finally {
            mResultLock.unlock();
        }
        scheduleUpdate();
    }

    private void onZoomChange(int zoom) {
        // 1000 needs to be in sync with the max values declared in the layout
        // xml file.
        mCurrentZoom = zoom / 1000f;
        scheduleUpdate();
    }

    private void onAngleChange(int angle) {
        mCurrentAngle = (float) Math.toRadians(angle);
        scheduleUpdate();
    }

    /** Delay-post a new preview rendering run. */
    private void scheduleUpdate() {
        mHandler.removeCallbacks(mCreateTinyPlanetRunnable);
        mHandler.postDelayed(mCreateTinyPlanetRunnable, RENDER_DELAY_MILLIS);
    }

    private InputStream getInputStream(Uri uri) {
        try {
            return requireContext().getContentResolver().openInputStream(uri);
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Could not load source image.", e);
        }
        return null;
    }

    /**
     * To create a proper TinyPlanet, the input image must be 2:1 (360:180 degrees). So if needed,
     * we pad the source image with black.
     */
    private static Bitmap createPaddedBitmap(Bitmap bitmapIn, XMPMeta xmp, int intermediateWidth) {
        try {
            int croppedAreaWidth = getInt(xmp, CROPPED_AREA_IMAGE_WIDTH_PIXELS);
            int croppedAreaHeight = getInt(xmp, CROPPED_AREA_IMAGE_HEIGHT_PIXELS);
            int fullPanoWidth = getInt(xmp, CROPPED_AREA_FULL_PANO_WIDTH_PIXELS);
            int fullPanoHeight = getInt(xmp, CROPPED_AREA_FULL_PANO_HEIGHT_PIXELS);
            int left = getInt(xmp, CROPPED_AREA_LEFT);
            int top = getInt(xmp, CROPPED_AREA_TOP);

            if (fullPanoWidth == 0 || fullPanoHeight == 0) {
                return bitmapIn;
            }
            // Make sure the intermediate image has the similar size to the
            // input.
            Bitmap paddedBitmap = null;
            float scale = intermediateWidth / (float) fullPanoWidth;
            while (paddedBitmap == null) {
                try {
                    paddedBitmap =
                            Bitmap.createBitmap(
                                    (int) (fullPanoWidth * scale),
                                    (int) (fullPanoHeight * scale),
                                    Bitmap.Config.ARGB_8888);
                } catch (OutOfMemoryError e) {
                    System.gc();
                    scale /= 2;
                }
            }
            Canvas paddedCanvas = new Canvas(paddedBitmap);

            int right = left + croppedAreaWidth;
            int bottom = top + croppedAreaHeight;
            RectF destRect = new RectF(left * scale, top * scale, right * scale, bottom * scale);
            paddedCanvas.drawBitmap(bitmapIn, null, destRect, null);
            return paddedBitmap;
        } catch (XMPException ex) {
            // Do nothing, just use mSourceBitmap as is.
        }
        return bitmapIn;
    }

    private static int getInt(XMPMeta xmp, String key) throws XMPException {
        if (xmp.doesPropertyExist(GOOGLE_PANO_NAMESPACE, key)) {
            return xmp.getPropertyInteger(GOOGLE_PANO_NAMESPACE, key);
        } else {
            return 0;
        }
    }
}
