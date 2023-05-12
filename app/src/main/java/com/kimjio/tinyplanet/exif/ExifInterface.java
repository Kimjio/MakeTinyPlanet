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

package com.kimjio.tinyplanet.exif;

import android.graphics.Bitmap;
import android.util.SparseIntArray;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteOrder;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.TimeZone;

/**
 * This class provides methods and constants for reading and writing jpeg file metadata. It contains
 * a collection of ExifTags, and a collection of definitions for creating valid ExifTags. The
 * collection of ExifTags can be updated by: reading new ones from a file, deleting or adding
 * existing ones, or building new ExifTags from a tag definition. These ExifTags can be written to a
 * valid jpeg image as exif metadata.
 *
 * <p>Each ExifTag has a tag ID (TID) and is stored in a specific image file directory (IFD) as
 * specified by the exif standard. A tag definition can be looked up with a constant that is a
 * combination of TID and IFD. This definition has information about the type, number of components,
 * and valid IFDs for a tag.
 *
 * @see ExifTag
 */
public class ExifInterface {
    public static final int IFD_NULL = -1;
    public static final int DEFINITION_NULL = 0;

    public static final int TAG_STRIP_OFFSETS = defineTag(IfdId.TYPE_IFD_0, (short) 0x0111);
    public static final int TAG_STRIP_BYTE_COUNTS = defineTag(IfdId.TYPE_IFD_0, (short) 0x0117);
    public static final int TAG_DATE_TIME = defineTag(IfdId.TYPE_IFD_0, (short) 0x0132);
    public static final int TAG_EXIF_IFD = defineTag(IfdId.TYPE_IFD_0, (short) 0x8769);
    public static final int TAG_GPS_IFD = defineTag(IfdId.TYPE_IFD_0, (short) 0x8825);
    // IFD 1
    public static final int TAG_JPEG_INTERCHANGE_FORMAT =
            defineTag(IfdId.TYPE_IFD_1, (short) 0x0201);
    public static final int TAG_JPEG_INTERCHANGE_FORMAT_LENGTH =
            defineTag(IfdId.TYPE_IFD_1, (short) 0x0202);
    public static final int TAG_DATE_TIME_ORIGINAL = defineTag(IfdId.TYPE_IFD_EXIF, (short) 0x9003);
    public static final int TAG_DATE_TIME_DIGITIZED =
            defineTag(IfdId.TYPE_IFD_EXIF, (short) 0x9004);
    public static final int TAG_USER_COMMENT = defineTag(IfdId.TYPE_IFD_EXIF, (short) 0x9286);
    public static final int TAG_INTEROPERABILITY_IFD =
            defineTag(IfdId.TYPE_IFD_EXIF, (short) 0xA005);

    /** Tags that contain offset markers. These are included in the banned defines. */
    private static final HashSet<Short> sOffsetTags = new HashSet<>();

    static {
        sOffsetTags.add(getTrueTagKey(TAG_GPS_IFD));
        sOffsetTags.add(getTrueTagKey(TAG_EXIF_IFD));
        sOffsetTags.add(getTrueTagKey(TAG_JPEG_INTERCHANGE_FORMAT));
        sOffsetTags.add(getTrueTagKey(TAG_INTEROPERABILITY_IFD));
        sOffsetTags.add(getTrueTagKey(TAG_STRIP_OFFSETS));
    }

    /** Returns the constant representing a tag with a given TID and default IFD. */
    public static int defineTag(int ifdId, short tagId) {
        return (tagId & 0x0000ffff) | (ifdId << 16);
    }

    /** Returns the TID for a tag constant. */
    public static short getTrueTagKey(int tag) {
        // Truncate
        return (short) tag;
    }

    /** Returns the default IFD for a tag constant. */
    public static int getTrueIfd(int tag) {
        return tag >>> 16;
    }

    private static final String NULL_ARGUMENT_STRING = "Argument is null";
    private final ExifData mData = new ExifData(DEFAULT_BYTE_ORDER);
    public static final ByteOrder DEFAULT_BYTE_ORDER = ByteOrder.BIG_ENDIAN;

    /**
     * Writes the tags from this ExifInterface object into a jpeg image, removing prior exif tags.
     *
     * @param jpeg a byte array containing a jpeg compressed image.
     * @param exifOutStream an OutputStream to which the jpeg image with added exif tags will be
     *     written.
     * @throws IOException
     */
    public void writeExif(byte[] jpeg, OutputStream exifOutStream) throws IOException {
        if (jpeg == null || exifOutStream == null) {
            throw new IllegalArgumentException(NULL_ARGUMENT_STRING);
        }
        OutputStream s = getExifWriterStream(exifOutStream);
        s.write(jpeg, 0, jpeg.length);
        s.flush();
    }

    /**
     * Writes the tags from this ExifInterface object into a jpeg compressed bitmap, removing prior
     * exif tags.
     *
     * @param bitmap a bitmap to compress and write exif into.
     * @param exifOutStream the OutputStream to which the jpeg image with added exif tags will be
     *     written.
     * @throws IOException
     */
    public void writeExif(Bitmap bitmap, OutputStream exifOutStream) throws IOException {
        if (bitmap == null || exifOutStream == null) {
            throw new IllegalArgumentException(NULL_ARGUMENT_STRING);
        }
        OutputStream s = getExifWriterStream(exifOutStream);
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, s);
        s.flush();
    }

    /**
     * Wraps an OutputStream object with an ExifOutputStream. Exif tags in this ExifInterface object
     * will be added to a jpeg image written to this stream, removing prior exif tags. Other methods
     * of this ExifInterface object should not be called until the returned OutputStream has been
     * closed.
     *
     * @param outStream an OutputStream to wrap.
     * @return an OutputStream that wraps the outStream parameter, and adds exif metadata. A jpeg
     *     image should be written to this stream.
     */
    public OutputStream getExifWriterStream(OutputStream outStream) {
        if (outStream == null) {
            throw new IllegalArgumentException(NULL_ARGUMENT_STRING);
        }
        ExifOutputStream eos = new ExifOutputStream(outStream, this);
        eos.setExifData(mData);
        return eos;
    }

    /**
     * Gets the default IFD for a tag.
     *
     * @param tagId a defined tag constant, e.g. {@link #TAG_DATE_TIME}.
     * @return the default IFD for a tag definition or {@link #IFD_NULL} if no definition exists.
     */
    public int getDefinedTagDefaultIfd(int tagId) {
        int info = getTagInfo().get(tagId);
        if (info == DEFINITION_NULL) {
            return IFD_NULL;
        }
        return getTrueIfd(tagId);
    }

    /**
     * Returns true if tag TID is one of the following: {@link TAG_EXIF_IFD}, {@link TAG_GPS_IFD},
     * {@link TAG_JPEG_INTERCHANGE_FORMAT}, {@link TAG_STRIP_OFFSETS}, {@link
     * TAG_INTEROPERABILITY_IFD}
     *
     * <p>Note: defining tags with these TID's is disallowed.
     *
     * @param tag a tag's TID (can be obtained from a defined tag constant with {@link
     *     #getTrueTagKey}).
     * @return true if the TID is that of an offset tag.
     */
    protected static boolean isOffsetTag(short tag) {
        return sOffsetTags.contains(tag);
    }

    /**
     * Creates a tag for a defined tag constant in a given IFD if that IFD is allowed for the tag.
     * This method will fail anytime the appropriate {@link ExifTag#setValue} for this tag's
     * datatype would fail.
     *
     * @param tagId a tag constant, e.g. {@link #TAG_DATE_TIME}.
     * @param ifdId the IFD that the tag should be in.
     * @param val the value of the tag to set.
     * @return an ExifTag object or null if one could not be constructed.
     * @see #buildTag
     */
    public ExifTag buildTag(int tagId, int ifdId, Object val) {
        int info = getTagInfo().get(tagId);
        if (info == 0 || val == null) {
            return null;
        }
        short type = getTypeFromInfo(info);
        int definedCount = getComponentCountFromInfo(info);
        boolean hasDefinedCount = (definedCount != ExifTag.SIZE_UNDEFINED);
        if (!ExifInterface.isIfdAllowed(info, ifdId)) {
            return null;
        }
        ExifTag t = new ExifTag(getTrueTagKey(tagId), type, definedCount, ifdId, hasDefinedCount);
        if (!t.setValue(val)) {
            return null;
        }
        return t;
    }

    /**
     * Creates a tag for a defined tag constant in the tag's default IFD.
     *
     * @param tagId a tag constant, e.g. {@link #TAG_DATE_TIME}.
     * @param val the tag's value.
     * @return an ExifTag object.
     */
    public ExifTag buildTag(int tagId, Object val) {
        int ifdId = getTrueIfd(tagId);
        return buildTag(tagId, ifdId, val);
    }

    protected ExifTag buildUninitializedTag(int tagId) {
        int info = getTagInfo().get(tagId);
        if (info == 0) {
            return null;
        }
        short type = getTypeFromInfo(info);
        int definedCount = getComponentCountFromInfo(info);
        boolean hasDefinedCount = (definedCount != ExifTag.SIZE_UNDEFINED);
        int ifdId = getTrueIfd(tagId);
        ExifTag t = new ExifTag(getTrueTagKey(tagId), type, definedCount, ifdId, hasDefinedCount);
        return t;
    }

    /**
     * Puts an ExifTag into this ExifInterface object's tags, removing a previous ExifTag with the
     * same TID and IFD. The IFD it is put into will be the one the tag was created with in {@link
     * #buildTag}.
     *
     * @param tag an ExifTag to put into this ExifInterface's tags.
     * @return the previous ExifTag with the same TID and IFD or null if none exists.
     */
    public ExifTag setTag(ExifTag tag) {
        return mData.addTag(tag);
    }

    protected int[] getTagDefinitionsForTagId(short tagId) {
        int[] ifds = IfdData.getIfds();
        int[] defs = new int[ifds.length];
        int counter = 0;
        SparseIntArray infos = getTagInfo();
        for (int i : ifds) {
            int def = defineTag(i, tagId);
            if (infos.get(def) != DEFINITION_NULL) {
                defs[counter++] = def;
            }
        }
        if (counter == 0) {
            return null;
        }

        return Arrays.copyOfRange(defs, 0, counter);
    }

    private static final String DATETIME_FORMAT_STR = "yyyy:MM:dd kk:mm:ss";
    private final DateFormat mDateTimeStampFormat = new SimpleDateFormat(DATETIME_FORMAT_STR);

    /**
     * Creates, formats, and sets the DateTimeStamp tag for one of: {@link #TAG_DATE_TIME}, {@link
     * #TAG_DATE_TIME_DIGITIZED}, {@link #TAG_DATE_TIME_ORIGINAL}.
     *
     * @param tagId one of the DateTimeStamp tags.
     * @param timestamp a timestamp to format, in ms.
     * @param timezone a TimeZone object.
     * @return true if success, false if the tag could not be set.
     */
    public boolean addDateTimeStampTag(int tagId, long timestamp, TimeZone timezone) {
        if (tagId == TAG_DATE_TIME
                || tagId == TAG_DATE_TIME_DIGITIZED
                || tagId == TAG_DATE_TIME_ORIGINAL) {
            mDateTimeStampFormat.setTimeZone(timezone);
            ExifTag t = buildTag(tagId, mDateTimeStampFormat.format(timestamp));
            if (t == null) {
                return false;
            }
            setTag(t);
        } else {
            return false;
        }
        return true;
    }

    private SparseIntArray mTagInfo = null;

    protected SparseIntArray getTagInfo() {
        if (mTagInfo == null) {
            mTagInfo = new SparseIntArray();
            initTagInfo();
        }
        return mTagInfo;
    }

    private void initTagInfo() {
        /**
         * We put tag information in a 4-bytes integer. The first byte a bitmask representing the
         * allowed IFDs of the tag, the second byte is the data type, and the last two byte are a
         * short value indicating the default component count of this tag.
         */
        // IFD0 tags
        int[] ifdAllowedIfds = {IfdId.TYPE_IFD_0, IfdId.TYPE_IFD_1};
        int ifdFlags = getFlagsFromAllowedIfds(ifdAllowedIfds) << 24;

        mTagInfo.put(ExifInterface.TAG_DATE_TIME, ifdFlags | ExifTag.TYPE_ASCII << 16 | 20);
        mTagInfo.put(ExifInterface.TAG_EXIF_IFD, ifdFlags | ExifTag.TYPE_UNSIGNED_LONG << 16 | 1);
        // Exif tags
        int[] exifAllowedIfds = {IfdId.TYPE_IFD_EXIF};
        int exifFlags = getFlagsFromAllowedIfds(exifAllowedIfds) << 24;
        mTagInfo.put(
                ExifInterface.TAG_DATE_TIME_ORIGINAL, exifFlags | ExifTag.TYPE_ASCII << 16 | 20);
        mTagInfo.put(
                ExifInterface.TAG_DATE_TIME_DIGITIZED, exifFlags | ExifTag.TYPE_ASCII << 16 | 20);
    }

    protected static int getAllowedIfdFlagsFromInfo(int info) {
        return info >>> 24;
    }

    protected static boolean isIfdAllowed(int info, int ifd) {
        int[] ifds = IfdData.getIfds();
        int ifdFlags = getAllowedIfdFlagsFromInfo(info);
        for (int i = 0; i < ifds.length; i++) {
            if (ifd == ifds[i] && ((ifdFlags >> i) & 1) == 1) {
                return true;
            }
        }
        return false;
    }

    protected static int getFlagsFromAllowedIfds(int[] allowedIfds) {
        if (allowedIfds == null || allowedIfds.length == 0) {
            return 0;
        }
        int flags = 0;
        int[] ifds = IfdData.getIfds();
        for (int i = 0; i < IfdId.TYPE_IFD_COUNT; i++) {
            for (int j : allowedIfds) {
                if (ifds[i] == j) {
                    flags |= 1 << i;
                    break;
                }
            }
        }
        return flags;
    }

    protected static short getTypeFromInfo(int info) {
        return (short) ((info >> 16) & 0x0ff);
    }

    protected static int getComponentCountFromInfo(int info) {
        return info & 0x0ffff;
    }
}
