package com.reactnativecommunity.webview;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Parcelable;
import android.provider.MediaStore;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.util.Pair;

import android.util.Log;
import android.webkit.MimeTypeMap;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.widget.Toast;

import com.facebook.common.activitylistener.ActivityListenerManager;
import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.modules.core.PermissionAwareActivity;
import com.facebook.react.modules.core.PermissionListener;

import java.io.File;
import java.io.IOException;
import java.lang.SecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

import static android.app.Activity.RESULT_OK;

public class RNCWebViewModuleImpl implements ActivityEventListener {
    public static final String NAME = "RNCWebViewModule";
    private static final String GOOGLE_PHOTOS_PACKAGE = "com.google.android.apps.photos";

    public static final int PICKER = 1;
    public static final int PICKER_LEGACY = 3;
    public static final int FILE_DOWNLOAD_PERMISSION_REQUEST = 1;
    public static final int FILE_UPLOAD_PERMISSION_REQUEST = 2;

    final private ReactApplicationContext mContext;

    private DownloadManager.Request mDownloadRequest;

    private ValueCallback<Uri> mFilePathCallbackLegacy;
    private ValueCallback<Uri[]> mFilePathCallback;
    private File mOutputImage;
    private File mOutputVideo;
    private String[] mPendingFileChooserAcceptTypes;
    private boolean mPendingFileChooserAllowMultiple;
    private boolean mPendingFileChooserCaptureEnabled;

    public RNCWebViewModuleImpl(ReactApplicationContext context) {
        mContext = context;
        context.addActivityEventListener(this);
    }

    @Override
    public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        if (mFilePathCallback == null && mFilePathCallbackLegacy == null) {
            return;
        }

        boolean imageTaken = false;
        boolean videoTaken = false;

        if (mOutputImage != null && mOutputImage.length() > 0) {
            imageTaken = true;
        }
        if (mOutputVideo != null && mOutputVideo.length() > 0) {
            videoTaken = true;
        }

        // based off of which button was pressed, we get an activity result and a file
        // the camera activity doesn't properly return the filename* (I think?) so we use
        // this filename instead
        switch (requestCode) {
            case RNCWebViewModuleImpl.PICKER:
                if (resultCode != RESULT_OK) {
                    if (mFilePathCallback != null) {
                        mFilePathCallback.onReceiveValue(null);
                    }
                } else {
                    if (imageTaken) {
                        mFilePathCallback.onReceiveValue(new Uri[]{getOutputUri(mOutputImage)});
                    } else if (videoTaken) {
                        mFilePathCallback.onReceiveValue(new Uri[]{getOutputUri(mOutputVideo)});
                    } else {
                        mFilePathCallback.onReceiveValue(getSelectedFiles(data, resultCode));
                    }
                }
                break;
            case RNCWebViewModuleImpl.PICKER_LEGACY:
                if (resultCode != RESULT_OK) {
                    mFilePathCallbackLegacy.onReceiveValue(null);
                } else {
                    if (imageTaken) {
                        mFilePathCallbackLegacy.onReceiveValue(getOutputUri(mOutputImage));
                    } else if (videoTaken) {
                        mFilePathCallbackLegacy.onReceiveValue(getOutputUri(mOutputVideo));
                    } else {
                        mFilePathCallbackLegacy.onReceiveValue(data.getData());
                    }
                }
                break;

        }

        if (mOutputImage != null && !imageTaken) {
            mOutputImage.delete();
        }
        if (mOutputVideo != null && !videoTaken) {
            mOutputVideo.delete();
        }

        mFilePathCallback = null;
        mFilePathCallbackLegacy = null;
        mOutputImage = null;
        mOutputVideo = null;
    }

    @Override
    public void onNewIntent(Intent intent) {

    }

    protected static class ShouldOverrideUrlLoadingLock {
        protected enum ShouldOverrideCallbackState {
            UNDECIDED,
            SHOULD_OVERRIDE,
            DO_NOT_OVERRIDE,
        }

        private double nextLockIdentifier = 1;
        private final HashMap<Double, AtomicReference<ShouldOverrideCallbackState>> shouldOverrideLocks = new HashMap<>();

        public synchronized Pair<Double, AtomicReference<ShouldOverrideCallbackState>> getNewLock() {
            final double lockIdentifier = nextLockIdentifier++;
            final AtomicReference<ShouldOverrideCallbackState> shouldOverride = new AtomicReference<>(ShouldOverrideCallbackState.UNDECIDED);
            shouldOverrideLocks.put(lockIdentifier, shouldOverride);
            return new Pair<>(lockIdentifier, shouldOverride);
        }

        @Nullable
        public synchronized AtomicReference<ShouldOverrideCallbackState> getLock(Double lockIdentifier) {
            return shouldOverrideLocks.get(lockIdentifier);
        }

        public synchronized void removeLock(Double lockIdentifier) {
            shouldOverrideLocks.remove(lockIdentifier);
        }
    }

    protected static final ShouldOverrideUrlLoadingLock shouldOverrideUrlLoadingLock = new ShouldOverrideUrlLoadingLock();

    private enum MimeType {
        DEFAULT("*/*"),
        IMAGE("image"),
        VIDEO("video");

        private final String value;

        MimeType(String value) {
            this.value = value;
        }
    }

    private PermissionListener getWebviewFileDownloaderPermissionListener(String downloadingMessage, String lackPermissionToDownloadMessage) {
        return new PermissionListener() {
            @Override
            public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
                switch (requestCode) {
                    case FILE_DOWNLOAD_PERMISSION_REQUEST: {
                        // If request is cancelled, the result arrays are empty.
                        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                            if (mDownloadRequest != null) {
                                downloadFile(downloadingMessage);
                            }
                        } else {
                            Toast.makeText(mContext, lackPermissionToDownloadMessage, Toast.LENGTH_LONG).show();
                        }
                        return true;
                    }
                }
                return false;
            }
        };
    }

    public boolean isFileUploadSupported() {
        return true;
    }

    public void shouldStartLoadWithLockIdentifier(boolean shouldStart, double lockIdentifier) {
        final AtomicReference<ShouldOverrideUrlLoadingLock.ShouldOverrideCallbackState> lockObject = shouldOverrideUrlLoadingLock.getLock(lockIdentifier);
        if (lockObject != null) {
            synchronized (lockObject) {
                lockObject.set(shouldStart ? ShouldOverrideUrlLoadingLock.ShouldOverrideCallbackState.DO_NOT_OVERRIDE : ShouldOverrideUrlLoadingLock.ShouldOverrideCallbackState.SHOULD_OVERRIDE);
                lockObject.notify();
            }
        }
    }

    public Uri[] getSelectedFiles(Intent data, int resultCode) {
        if (data == null) {
            return null;
        }

        // we have multiple files selected
        if (data.getClipData() != null) {
            final int numSelectedFiles = data.getClipData().getItemCount();
            Uri[] result = new Uri[numSelectedFiles];
            for (int i = 0; i < numSelectedFiles; i++) {
                result[i] = data.getClipData().getItemAt(i).getUri();
            }
            return result;
        }

        // we have one file selected
        if (data.getData() != null && resultCode == RESULT_OK && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return WebChromeClient.FileChooserParams.parseResult(resultCode, data);
        }

        return null;
    }

    public void startPhotoPickerIntent(String acceptType, ValueCallback<Uri> callback) {
        mFilePathCallbackLegacy = callback;
        Activity activity = mContext.getCurrentActivity();
        if (activity == null) {
            Log.w("RNCWebViewModule", "there is no Activity to handle this Intent");
            return;
        }
        Intent fileChooserIntent = getFileChooserIntent(acceptType);
        Intent chooserIntent = Intent.createChooser(fileChooserIntent, "");

        ArrayList<Parcelable> extraIntents = new ArrayList<>();
        Intent photoIntent = getPhotoIntent();
        if (photoIntent != null) {
            extraIntents.add(photoIntent);
        }
        Intent videoIntent = getVideoIntent();
        if (videoIntent != null) {
            extraIntents.add(videoIntent);
        }
        chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, extraIntents.toArray(new Parcelable[0]));
        excludeGooglePhotosFromChooser(chooserIntent, fileChooserIntent, activity);

        if (chooserIntent.resolveActivity(activity.getPackageManager()) != null) {
            activity.startActivityForResult(chooserIntent, PICKER_LEGACY);
        } else {
            Log.w("RNCWebViewModule", "there is no Activity to handle this Intent");
        }
    }

    public boolean startPhotoPickerIntent(final String[] acceptTypes, final boolean allowMultiple, final ValueCallback<Uri[]> callback, final boolean isCaptureEnabled) {
        mFilePathCallback = callback;
        Activity activity = mContext.getCurrentActivity();
        String[] normalizedAcceptTypes = acceptTypes != null ? acceptTypes : new String[0];
        if (activity == null) {
            Log.w("RNCWebViewModule", "there is no Activity to handle this Intent");
            return false;
        }

        if (needsCameraPermission()) {
            setPendingFileChooserRequest(normalizedAcceptTypes, allowMultiple, isCaptureEnabled);
            requestFileUploadPermissions();
            return true;
        }

        return startPhotoPickerIntent(activity, normalizedAcceptTypes, allowMultiple, isCaptureEnabled, true);
    }

    public void setDownloadRequest(DownloadManager.Request request) {
        mDownloadRequest = request;
    }

    public void downloadFile(String downloadingMessage) {
        DownloadManager dm = (DownloadManager) mContext.getSystemService(Context.DOWNLOAD_SERVICE);

        try {
            dm.enqueue(mDownloadRequest);
        } catch (IllegalArgumentException | SecurityException e) {
            Log.w("RNCWebViewModule", "Unsupported URI, aborting download", e);
            return;
        }

        Toast.makeText(mContext, downloadingMessage, Toast.LENGTH_LONG).show();
    }

    public boolean grantFileDownloaderPermissions(String downloadingMessage, String lackPermissionToDownloadMessage) {
        Activity activity = mContext.getCurrentActivity();
        // Permission not required for Android Q and above
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            return true;
        }

        boolean result = ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        if (!result && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PermissionAwareActivity PAactivity = getPermissionAwareActivity();
            PAactivity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, FILE_DOWNLOAD_PERMISSION_REQUEST, getWebviewFileDownloaderPermissionListener(downloadingMessage, lackPermissionToDownloadMessage));
        }

        return result;
    }

    protected boolean needsCameraPermission() {
        Activity activity = mContext.getCurrentActivity();
        boolean needed = false;

        PackageManager packageManager = activity.getPackageManager();
        try {
            String[] requestedPermissions = packageManager.getPackageInfo(activity.getApplicationContext().getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions;
            if (Arrays.asList(requestedPermissions).contains(Manifest.permission.CAMERA)
                    && ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                needed = true;
            }
        } catch (PackageManager.NameNotFoundException e) {
            needed = true;
        }

        return needed;
    }

    public Intent getPhotoIntent() {
        Intent intent = null;

        try {
            mOutputImage = getCapturedFile(MimeType.IMAGE);
            Uri outputImageUri = getOutputUri(mOutputImage);
            intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, outputImageUri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (IOException | IllegalArgumentException e) {
            Log.e("CREATE FILE", "Error occurred while creating the File", e);
            e.printStackTrace();
        }

        return intent;
    }

    public Intent getVideoIntent() {
        Intent intent = null;

        try {
            mOutputVideo = getCapturedFile(MimeType.VIDEO);
            Uri outputVideoUri = getOutputUri(mOutputVideo);
            intent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, outputVideoUri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (IOException | IllegalArgumentException e) {
            Log.e("CREATE FILE", "Error occurred while creating the File", e);
            e.printStackTrace();
        }

        return intent;
    }

    private Intent getFileChooserIntent(String acceptTypes) {
        return createGalleryIntent(acceptsImages(acceptTypes), acceptsVideo(acceptTypes), false);
    }

    private Intent getFileChooserIntent(String[] acceptTypes, boolean allowMultiple) {
        return createGalleryIntent(acceptsImages(acceptTypes), acceptsVideo(acceptTypes), allowMultiple);
    }

    private Intent createGalleryIntent(boolean includeImages, boolean includeVideo, boolean allowMultiple) {
        boolean useImageGallery = includeImages || !includeVideo;
        Uri galleryUri = useImageGallery
                ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String mimeType = useImageGallery ? "image/*" : "video/*";

        Intent pickIntent = new Intent(Intent.ACTION_PICK, galleryUri);
        pickIntent.setType(mimeType);

        if (includeImages && includeVideo) {
            pickIntent.putExtra(Intent.EXTRA_MIME_TYPES, getChooserMimeTypes());
        }

        if (allowMultiple && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            pickIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }

        Activity activity = mContext.getCurrentActivity();
        if (activity != null) {
            ComponentName galleryComponent = getPreferredGalleryComponent(pickIntent, activity.getPackageManager());
            if (galleryComponent != null) {
                pickIntent.setComponent(galleryComponent);
            }
        }

        return pickIntent;
    }

    private String[] getChooserMimeTypes() {
        return new String[]{"image/*", "video/*"};
    }

    private Boolean acceptsImages(String types) {
        String mimeType = types;
        if (types.matches("\\.\\w+")) {
            mimeType = getMimeTypeFromExtension(types.replace(".", ""));
        }
        return mimeType.isEmpty() || mimeType.toLowerCase().contains(MimeType.IMAGE.value);
    }

    private Boolean acceptsImages(String[] types) {
        String[] mimeTypes = getAcceptedMimeType(types);
        return arrayContainsString(mimeTypes, MimeType.DEFAULT.value) || arrayContainsString(mimeTypes, MimeType.IMAGE.value);
    }

    private Boolean acceptsVideo(String types) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }

        String mimeType = types;
        if (types.matches("\\.\\w+")) {
            mimeType = getMimeTypeFromExtension(types.replace(".", ""));
        }
        return mimeType.isEmpty() || mimeType.toLowerCase().contains(MimeType.VIDEO.value);
    }

    private Boolean acceptsVideo(String[] types) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }

        String[] mimeTypes = getAcceptedMimeType(types);
        return arrayContainsString(mimeTypes, MimeType.DEFAULT.value) || arrayContainsString(mimeTypes, MimeType.VIDEO.value);
    }

    private Boolean arrayContainsString(String[] array, String pattern) {
        for (String content : array) {
            if (content.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private String[] getAcceptedMimeType(String[] types) {
        if (noAcceptTypesSet(types)) {
            return new String[]{MimeType.DEFAULT.value};
        }
        String[] mimeTypes = new String[types.length];
        for (int i = 0; i < types.length; i++) {
            String t = types[i];
            // convert file extensions to mime types
            if (t.matches("\\.\\w+")) {
                String mimeType = getMimeTypeFromExtension(t.replace(".", ""));
                if(mimeType != null) {
                    mimeTypes[i] = mimeType;
                } else {
                    mimeTypes[i] = t;
                }
            } else {
                mimeTypes[i] = t;
            }
        }
        return mimeTypes;
    }

    private String getMimeTypeFromExtension(String extension) {
        String type = null;
        if (extension != null) {
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        }
        return type;
    }

    public Uri getOutputUri(File capturedFile) {
        // for versions below 6.0 (23) we use the old File creation & permissions model
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return Uri.fromFile(capturedFile);
        }

        // for versions 6.0+ (23) we use the FileProvider to avoid runtime permissions
        String packageName = mContext.getPackageName();
        return FileProvider.getUriForFile(mContext, packageName + ".fileprovider", capturedFile);
    }

    public File getCapturedFile(MimeType mimeType) throws IOException {
        String prefix = "";
        String suffix = "";
        String dir = "";

        switch (mimeType) {
            case IMAGE:
                prefix = "image-";
                suffix = ".jpg";
                dir = Environment.DIRECTORY_PICTURES;
                break;
            case VIDEO:
                prefix = "video-";
                suffix = ".mp4";
                dir = Environment.DIRECTORY_MOVIES;
                break;

            default:
                break;
        }

        String filename = prefix + String.valueOf(System.currentTimeMillis()) + suffix;
        File outputFile = null;

        // for versions below 6.0 (23) we use the old File creation & permissions model
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // only this Directory works on all tested Android versions
            // ctx.getExternalFilesDir(dir) was failing on Android 5.0 (sdk 21)
            File storageDir = Environment.getExternalStoragePublicDirectory(dir);
            outputFile = new File(storageDir, filename);
        } else {
            File storageDir = mContext.getExternalFilesDir(null);
            outputFile = File.createTempFile(prefix, suffix, storageDir);
        }

        return outputFile;
    }

    private Boolean noAcceptTypesSet(String[] types) {
        // when our array returned from getAcceptTypes() has no values set from the webview
        // i.e. <input type="file" />, without any "accept" attr
        // will be an array with one empty string element, afaik

        return types.length == 0 || (types.length == 1 && types[0] != null && types[0].length() == 0);
    }

    private PermissionListener getWebviewFileUploaderPermissionListener() {
        return new PermissionListener() {
            @Override
            public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
                switch (requestCode) {
                    case FILE_UPLOAD_PERMISSION_REQUEST: {
                        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;

                        if (granted) {
                            resumePendingPhotoPickerIntent(true);
                        } else if (mPendingFileChooserCaptureEnabled) {
                            if (mFilePathCallback != null) {
                                mFilePathCallback.onReceiveValue(null);
                            }
                            mFilePathCallback = null;
                            clearPendingFileChooserRequest();
                        } else {
                            resumePendingPhotoPickerIntent(false);
                        }
                        return true;
                    }
                }
                return false;
            }
        };
    }

    private void requestFileUploadPermissions() {
        PermissionAwareActivity activity = getPermissionAwareActivity();
        activity.requestPermissions(
                new String[]{Manifest.permission.CAMERA},
                FILE_UPLOAD_PERMISSION_REQUEST,
                getWebviewFileUploaderPermissionListener()
        );
    }

    private void setPendingFileChooserRequest(String[] acceptTypes, boolean allowMultiple, boolean isCaptureEnabled) {
        mPendingFileChooserAcceptTypes = acceptTypes != null ? acceptTypes.clone() : new String[0];
        mPendingFileChooserAllowMultiple = allowMultiple;
        mPendingFileChooserCaptureEnabled = isCaptureEnabled;
    }

    private void clearPendingFileChooserRequest() {
        mPendingFileChooserAcceptTypes = null;
        mPendingFileChooserAllowMultiple = false;
        mPendingFileChooserCaptureEnabled = false;
    }

    private void resumePendingPhotoPickerIntent(boolean includeCameraIntents) {
        Activity activity = mContext.getCurrentActivity();
        if (activity == null) {
            Log.w("RNCWebViewModule", "there is no Activity to handle this Intent");
            if (mFilePathCallback != null) {
                mFilePathCallback.onReceiveValue(null);
            }
            mFilePathCallback = null;
            clearPendingFileChooserRequest();
            return;
        }

        String[] acceptTypes = mPendingFileChooserAcceptTypes != null ? mPendingFileChooserAcceptTypes : new String[0];
        boolean allowMultiple = mPendingFileChooserAllowMultiple;
        boolean isCaptureEnabled = mPendingFileChooserCaptureEnabled;

        clearPendingFileChooserRequest();
        startPhotoPickerIntent(activity, acceptTypes, allowMultiple, isCaptureEnabled, includeCameraIntents);
    }

    private boolean startPhotoPickerIntent(Activity activity, String[] acceptTypes, boolean allowMultiple, boolean isCaptureEnabled, boolean includeCameraIntents) {
        Intent chooserIntent = getPhotoPickerIntent(activity, acceptTypes, allowMultiple, isCaptureEnabled, includeCameraIntents);

        if (chooserIntent == null) {
            Log.w("RNCWebViewModule", "there is no Camera permission");
            return false;
        }

        if (chooserIntent.resolveActivity(activity.getPackageManager()) != null) {
            activity.startActivityForResult(chooserIntent, PICKER);
        } else {
            Log.w("RNCWebViewModule", "there is no Activity to handle this Intent");
        }

        return true;
    }

    private Intent getPhotoPickerIntent(Activity activity, String[] acceptTypes, boolean allowMultiple, boolean isCaptureEnabled, boolean includeCameraIntents) {
        if (isCaptureEnabled) {
            return includeCameraIntents ? getCaptureIntent(acceptTypes) : null;
        }

        Intent chooserIntent = new Intent(Intent.ACTION_CHOOSER);
        Intent fileSelectionIntent = getFileChooserIntent(acceptTypes, allowMultiple);
        chooserIntent.putExtra(Intent.EXTRA_INTENT, fileSelectionIntent);
        excludeGooglePhotosFromChooser(chooserIntent, fileSelectionIntent, activity);

        if (includeCameraIntents) {
            ArrayList<Parcelable> extraIntents = new ArrayList<>();
            Intent photoIntent = getPhotoIntent();
            if (photoIntent != null) {
                extraIntents.add(photoIntent);
            }
            Intent videoIntent = getVideoIntent();
            if (videoIntent != null) {
                extraIntents.add(videoIntent);
            }
            chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, extraIntents.toArray(new Parcelable[0]));
        }

        return chooserIntent;
    }

    private void excludeGooglePhotosFromChooser(Intent chooserIntent, Intent targetIntent, Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return;
        }

        ComponentName[] excludedComponents = getExcludedChooserComponents(targetIntent, activity.getPackageManager());
        if (excludedComponents.length > 0) {
            chooserIntent.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, excludedComponents);
        }
    }

    private ComponentName[] getExcludedChooserComponents(Intent targetIntent, PackageManager packageManager) {
        ArrayList<ComponentName> excludedComponents = new ArrayList<>();
        for (ResolveInfo resolveInfo : packageManager.queryIntentActivities(targetIntent, PackageManager.MATCH_DEFAULT_ONLY)) {
            if (resolveInfo.activityInfo != null
                    && GOOGLE_PHOTOS_PACKAGE.equals(resolveInfo.activityInfo.packageName)) {
                excludedComponents.add(new ComponentName(resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name));
            }
        }
        return excludedComponents.toArray(new ComponentName[0]);
    }

    private ComponentName getPreferredGalleryComponent(Intent targetIntent, PackageManager packageManager) {
        ResolveInfo fallbackResolveInfo = null;

        for (ResolveInfo resolveInfo : packageManager.queryIntentActivities(targetIntent, PackageManager.MATCH_DEFAULT_ONLY)) {
            if (resolveInfo.activityInfo == null) {
                continue;
            }

            String packageName = resolveInfo.activityInfo.packageName;
            if (GOOGLE_PHOTOS_PACKAGE.equals(packageName)) {
                continue;
            }

            if (fallbackResolveInfo == null) {
                fallbackResolveInfo = resolveInfo;
            }

            String normalizedPackageName = packageName.toLowerCase();
            if (normalizedPackageName.contains("gallery")
                    || normalizedPackageName.contains("album")
                    || normalizedPackageName.contains("media")) {
                return new ComponentName(resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name);
            }
        }

        if (fallbackResolveInfo == null || fallbackResolveInfo.activityInfo == null) {
            return null;
        }

        return new ComponentName(fallbackResolveInfo.activityInfo.packageName, fallbackResolveInfo.activityInfo.name);
    }

    private Intent getCaptureIntent(String[] acceptTypes) {
        boolean supportsImages = acceptsImages(acceptTypes);
        boolean supportsVideo = acceptsVideo(acceptTypes);

        if (supportsImages) {
            Intent photoIntent = getPhotoIntent();
            if (photoIntent != null) {
                return photoIntent;
            }
        }

        if (supportsVideo) {
            return getVideoIntent();
        }

        return null;
    }

    private PermissionAwareActivity getPermissionAwareActivity() {
        Activity activity = mContext.getCurrentActivity();
        if (activity == null) {
            throw new IllegalStateException("Tried to use permissions API while not attached to an Activity.");
        } else if (!(activity instanceof PermissionAwareActivity)) {
            throw new IllegalStateException("Tried to use permissions API but the host Activity doesn't implement PermissionAwareActivity.");
        }
        return (PermissionAwareActivity) activity;
    }
}
