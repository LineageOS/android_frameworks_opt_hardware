/*
 * Copyright (C) 2014 The CyanogenMod Project
 * Copyright (C) 2018 The LineageOS Project
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

package org.lineageos.hardware;

import android.app.ActivityThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.util.Slog;

import com.android.server.LocalServices;
import com.android.server.display.DisplayTransformManager;
import static com.android.server.display.DisplayTransformManager.LEVEL_COLOR_MATRIX_NIGHT_DISPLAY;

import org.lineageos.internal.util.FileUtils;

public class DisplayColorCalibration {

    private static final String TAG = "DisplayColorCalibration";

    private static final String COLOR_FILE = "/sys/class/graphics/fb0/rgb";

    private static final int LEVEL_COLOR_MATRIX_LIVEDISPLAY = LEVEL_COLOR_MATRIX_NIGHT_DISPLAY + 1;

    private static final boolean sUseHWC2ColorTransform;
    private static final boolean sUseGPUMode;

    private static final int MIN = 255;
    private static final int MAX = 32768;

    private static final int[] sCurColors = new int[] { MAX, MAX, MAX };

    private static DisplayTransformManager sDTMService;

    static {
        // We use HWC2 color transform if possible.
        sUseHWC2ColorTransform = ActivityThread.currentApplication().
                getApplicationContext().getResources().getBoolean(
                        com.android.internal.R.bool.config_setColorTransformAccelerated);
        // We can also support GPU transform using RenderEngine. This is not
        // preferred though, as it has a high power cost.
        sUseGPUMode = !FileUtils.isFileWritable(COLOR_FILE) ||
                SystemProperties.getBoolean("debug.livedisplay.force_gpu", false);
    }

    public static boolean isSupported() {
        // Always true, use GPU mode if no HW support
        return true;
    }

    public static int getMaxValue()  {
        return MAX;
    }

    public static int getMinValue()  {
        return MIN;
    }

    public static int getDefValue() {
        return getMaxValue();
    }

    public static String getCurColors()  {
        if (!sUseGPUMode && !sUseHWC2ColorTransform) {
            return FileUtils.readOneLine(COLOR_FILE);
        }

        return String.format("%d %d %d", sCurColors[0],
                sCurColors[1], sCurColors[2]);
    }

    public static boolean setColors(String colors) {
        if (!sUseGPUMode && !sUseHWC2ColorTransform) {
            return FileUtils.writeLine(COLOR_FILE, colors);
        }

        float[] mat = toColorMatrix(colors);

        if (sUseHWC2ColorTransform) {
            if (sDTMService == null) {
                sDTMService = LocalServices.getService(DisplayTransformManager.class);
                if (sDTMService == null) {
                    return false;
                }
            }
            float[] rgb = new float[] {mat[0], mat[5], mat[10], mat[15]};
            sDTMService.setColorMatrix(LEVEL_COLOR_MATRIX_LIVEDISPLAY, mat);
            return true;
        }

        // set to null if identity
        if (mat == null ||
                (mat[0] == 1.0f && mat[5] == 1.0f &&
                 mat[10] == 1.0f && mat[15] == 1.0f)) {
            return setColorTransform(null);
        }
        return setColorTransform(mat);
    }

    private static float[] toColorMatrix(String rgbString) {
        String[] adj = rgbString == null ? null : rgbString.split(" ");

        if (adj == null || adj.length != 3) {
            return null;
        }

        float[] mat = new float[16];

        // sanity check
        for (int i = 0; i < 3; i++) {
            int v = Integer.parseInt(adj[i]);

            if (v >= MAX) {
                v = MAX;
            } else if (v < MIN) {
                v = MIN;
            }

            mat[i * 5] = (float)v / (float)MAX;
            sCurColors[i] = v;
        }

        mat[15] = 1.0f;
        return mat;
    }

    /**
     * Sets the surface flinger's color transformation as a 4x4 matrix. If the
     * matrix is null, color transformations are disabled.
     *
     * @param m the float array that holds the transformation matrix, or null to
     *            disable transformation
     */
    private static boolean setColorTransform(float[] m) {
        try {
            final IBinder flinger = ServiceManager.getService("SurfaceFlinger");
            if (flinger != null) {
                final Parcel data = Parcel.obtain();
                data.writeInterfaceToken("android.ui.ISurfaceComposer");
                if (m != null) {
                    data.writeInt(1);
                    for (int i = 0; i < 16; i++) {
                        data.writeFloat(m[i]);
                    }
                } else {
                    data.writeInt(0);
                }
                flinger.transact(1030, data, null, 0);
                data.recycle();
            }
        } catch (RemoteException ex) {
            Slog.e(TAG, "Failed to set color transform", ex);
            return false;
        }
        return true;
    }

}
