package com.platform.middlewares.plugins;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.FileProvider;
import android.util.Log;
import android.widget.Toast;

import com.breadwallet.BreadWalletApp;
import com.breadwallet.R;
import com.breadwallet.presenter.activities.MainActivity;
import com.breadwallet.tools.crypto.CryptoHelper;
import com.breadwallet.tools.security.KeyStoreManager;
import com.breadwallet.tools.util.BRConstants;
import com.jniwrappers.BRBase58;
import com.jniwrappers.BRKey;
import com.platform.interfaces.Plugin;

import org.apache.commons.compress.utils.IOUtils;
import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.eclipse.jetty.server.Request;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static android.R.attr.key;
import static com.breadwallet.R.string.request;
import static com.breadwallet.tools.util.BRConstants.REQUEST_IMAGE_CAPTURE;
import static com.platform.APIClient.bundleFileName;

/**
 * BreadWallet
 * <p/>
 * Created by Mihail Gutan on <mihail@breadwallet.com> 11/2/16.
 * Copyright (c) 2016 breadwallet LLC
 * <p/>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p/>
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * <p/>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
public class CameraPlugin implements Plugin {
    public static final String TAG = CameraPlugin.class.getName();

    private static Request globalBaseRequest;
    private static Continuation continuation;

    @Override
    public boolean handle(String target, final Request baseRequest, final HttpServletRequest request, final HttpServletResponse response) {
        // GET /_camera/take_picture
        //
        // Optionally pass ?overlay=<id> (see overlay ids below) to show an overlay
        // in picture taking mode
        //
        // Status codes:
        //   - 200: Successful image capture
        //   - 204: User canceled image picker
        //   - 404: Camera is not available on this device
        //   - 423: Multiple concurrent take_picture requests. Only one take_picture request may be in flight at once.
        //
        final MainActivity app = MainActivity.app;
        if (app == null) {
            try {
                response.sendError(500, "context is null");
            } catch (IOException e) {
                e.printStackTrace();
            }
            return true;
        }
        if (target.startsWith("/_camera/take_picture")) {
            Log.e(TAG, "handle: /_camera/take_picture");
            if (globalBaseRequest != null) {
                try {
                    Log.e(TAG, "handle: already taking a picture");
                    response.sendError(423);
                    baseRequest.setHandled(true);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                return true;
            }

            PackageManager pm = app.getPackageManager();

            if (!pm.hasSystemFeature(PackageManager.FEATURE_CAMERA)) {
                Log.e(TAG, "handle: no camera available");
                baseRequest.setHandled(true);
                try {
                    response.sendError(402);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                return true;
            }
//            app.runOnUiThread(new Runnable() {
//                @Override
//                public void run() {
                    if (ContextCompat.checkSelfPermission(app,
                            Manifest.permission.CAMERA)
                            != PackageManager.PERMISSION_GRANTED) {
                        // Should we show an explanation?
                        if (ActivityCompat.shouldShowRequestPermissionRationale(app,
                                Manifest.permission.CAMERA)) {
                            ((BreadWalletApp) app.getApplication()).showCustomToast(app,
                                    app.getString(R.string.allow_camera_access),
                                    MainActivity.screenParametersPoint.y / 2, Toast.LENGTH_LONG, 0);
                        } else {
                            // No explanation needed, we can request the permission.
                            ActivityCompat.requestPermissions(app,
                                    new String[]{Manifest.permission.CAMERA},
                                    BRConstants.CAMERA_REQUEST_GLIDERA_ID);
                        }
                    } else {
                        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        if (takePictureIntent.resolveActivity(app.getPackageManager()) != null) {
                            app.startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE);
                        }

                        continuation = ContinuationSupport.getContinuation(request);
                        continuation.suspend(response);
                        globalBaseRequest = baseRequest;
                    }
//                }
//            });

            return true;
        } else if (target.startsWith("/_camera/picture/")) {
            Log.e(TAG, "handle: /_camera/picture/");
            String id = target.replace("/_camera/picture/", "");
            byte[] pictureBytes = readPictureForId(app, id);
            if (pictureBytes == null) {
                Log.e(TAG, "handle: WARNING pictureBytes is null");
                try {
                    response.sendError(500);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                return true;
            }
            try {
                response.getOutputStream().write(pictureBytes);
            } catch (IOException e) {
                e.printStackTrace();
            }
            response.setStatus(200);
            return true;
        } else return false;
    }

    public static void handleCameraImageTaken(Context context, Bitmap img) {
        Log.e(TAG, "handleCameraImageTaken: ");
        if (globalBaseRequest == null || continuation == null) {

            Log.e(TAG, "handleCameraImageTaken: WARNING: " + continuation + " " + globalBaseRequest);
            return;
        }
        try {
            if (img == null) {
                globalBaseRequest.setHandled(true);
                ((HttpServletResponse) continuation.getServletResponse()).setStatus(204);
                return;
            }
            String id = writeToFile(context, img);
            if (id != null) {
                JSONObject respJson = new JSONObject();
                try {
                    respJson.put("id", id);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                Log.e(TAG, "handleCameraImageTaken: wrote image to: " + id);
                try {
                    continuation.getServletResponse().getWriter().write(respJson.toString());
                    ((HttpServletResponse) continuation.getServletResponse()).setStatus(200);
                } catch (IOException e) {
                    e.printStackTrace();
                }

            } else {
                Log.e(TAG, "handleCameraImageTaken: error writing image");
                try {
                    ((HttpServletResponse) continuation.getServletResponse()).sendError(500);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        } finally {
            globalBaseRequest.setHandled(true);
            if (continuation != null)
                continuation.complete();
            globalBaseRequest = null;
            continuation = null;
        }

    }

    private static String writeToFile(Context context, Bitmap img) {
        String name = null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FileOutputStream fileOutputStream = null;
        try {
//            out = new FileOutputStream(image);
            img.compress(Bitmap.CompressFormat.JPEG, 50, out);

            name = CryptoHelper.base58ofSha256(out.toByteArray());

            File storageDir = new File(context.getFilesDir().getAbsolutePath() + "/pictures/");
            storageDir.mkdir();
            File image = File.createTempFile(
                    name,  /* prefix */
                    ".jpeg",         /* suffix */
                    storageDir      /* directory */
            );

            fileOutputStream = new FileOutputStream(image);
            fileOutputStream.write(out.toByteArray());
            return name;
            // PNG is a lossless format, the compression factor (100) is ignored
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (fileOutputStream != null) fileOutputStream.close();
                out.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    public byte[] readPictureForId(Context context, String id) {

        try {
            //create FileInputStream object
            FileInputStream fin = new FileInputStream(new File(context.getFilesDir().getAbsolutePath() + "/pictures/" + id));

            //create string from byte array
            return IOUtils.toByteArray(fin);

        } catch (FileNotFoundException e) {
            System.out.println("File not found" + e);
        } catch (IOException ioe) {
            System.out.println("Exception while reading the file " + ioe);
        }
        return null;
    }
}
