package com.urbangroupent.urbancharadesgame;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import androidx.core.content.FileProvider;

import com.getcapacitor.BridgeActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class MainActivity extends BridgeActivity {
    private File pendingVideoFile;
    private FileOutputStream pendingVideoStream;
    private StringBuilder pendingBase64Carry;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getBridge().getWebView().addJavascriptInterface(new NativeVideoShareBridge(), "NativeVideoShare");
    }

    private synchronized void closePendingVideoStream() {
        if (pendingVideoStream != null) {
            try {
                pendingVideoStream.flush();
            } catch (IOException ignored) {}

            try {
                pendingVideoStream.close();
            } catch (IOException ignored) {}

            pendingVideoStream = null;
        }

        pendingBase64Carry = null;
    }

    private String sanitizeBase64Chunk(String value) {
        if (value == null) return "";

        int commaIndex = value.indexOf(',');
        String chunk = commaIndex >= 0 ? value.substring(commaIndex + 1) : value;
        StringBuilder sanitized = new StringBuilder(chunk.length());

        for (int i = 0; i < chunk.length(); i++) {
            char c = chunk.charAt(i);
            if (Character.isWhitespace(c)) continue;
            if (c == '-') {
                sanitized.append('+');
            } else if (c == '_') {
                sanitized.append('/');
            } else if (
                (c >= 'A' && c <= 'Z') ||
                (c >= 'a' && c <= 'z') ||
                (c >= '0' && c <= '9') ||
                c == '+' ||
                c == '/' ||
                c == '='
            ) {
                sanitized.append(c);
            } else {
                // Ignore non-base64 characters defensively. Android's decoder is strict and
                // the WebView bridge can surface copied data URL prefixes or separators.
            }
        }

        return sanitized.toString();
    }

    private void decodePendingBase64(boolean finish) throws IOException {
        if (pendingVideoStream == null || pendingBase64Carry == null || pendingBase64Carry.length() == 0) return;

        int decodeLength = (pendingBase64Carry.length() / 4) * 4;

        if (!finish) {
            int paddingIndex = pendingBase64Carry.indexOf("=");
            if (paddingIndex >= 0) {
                decodeLength = (paddingIndex / 4) * 4;
            }
        } else if (pendingBase64Carry.length() % 4 != 0) {
            while (pendingBase64Carry.length() % 4 != 0) {
                pendingBase64Carry.append('=');
            }
            decodeLength = pendingBase64Carry.length();
        }

        if (decodeLength == 0) return;

        String base64Block = pendingBase64Carry.substring(0, decodeLength);
        byte[] bytes = Base64.decode(base64Block, Base64.DEFAULT);
        pendingVideoStream.write(bytes);
        pendingBase64Carry.delete(0, decodeLength);
    }
    private String getVideoMimeType(String fileName, String requestedMimeType) {
        if (requestedMimeType != null && requestedMimeType.startsWith("video/")) {
            return requestedMimeType.split(";")[0].trim();
        }
        String lowerName = fileName == null ? "" : fileName.toLowerCase();
        return lowerName.endsWith(".mp4") ? "video/mp4" : "video/webm";
    }

    private Uri savePendingVideoToGallery(String displayName, String requestedMimeType) throws IOException {
        if (pendingVideoFile == null || !pendingVideoFile.exists()) {
            throw new IOException("Video file was not created.");
        }

        String safeName = displayName == null || displayName.length() == 0
            ? pendingVideoFile.getName()
            : displayName.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (!safeName.contains(".")) {
            safeName += pendingVideoFile.getName().toLowerCase().endsWith(".mp4") ? ".mp4" : ".webm";
        }

        ContentResolver resolver = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, safeName);
        values.put(MediaStore.Video.Media.MIME_TYPE, getVideoMimeType(safeName, requestedMimeType));
        values.put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Urban Charades");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
        }

        Uri savedUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (savedUri == null) throw new IOException("Could not create video in gallery.");

        try (FileInputStream input = new FileInputStream(pendingVideoFile);
             OutputStream output = resolver.openOutputStream(savedUri)) {
            if (output == null) throw new IOException("Could not open gallery video for writing.");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        } catch (IOException e) {
            resolver.delete(savedUri, null, null);
            throw e;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues doneValues = new ContentValues();
            doneValues.put(MediaStore.Video.Media.IS_PENDING, 0);
            resolver.update(savedUri, doneValues, null, null);
        }

        return savedUri;
    }

    public class NativeVideoShareBridge {
        @JavascriptInterface
        public synchronized String beginVideoFile(String fileName) {
            try {
                closePendingVideoStream();

                String safeName = fileName == null ? "urban-charades-recording.webm" : fileName;
                safeName = safeName.replaceAll("[^a-zA-Z0-9._-]", "_");
                if (safeName.length() == 0) safeName = "urban-charades-recording.webm";

                File dir = new File(getCacheDir(), "urban-charades");
                if (!dir.exists() && !dir.mkdirs()) {
                    return "ERROR: Could not create cache directory.";
                }

                pendingVideoFile = new File(dir, safeName);
                pendingVideoStream = new FileOutputStream(pendingVideoFile, false);
                pendingBase64Carry = new StringBuilder();
                return "OK";
            } catch (Exception e) {
                closePendingVideoStream();
                return "ERROR: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public synchronized String appendVideoChunk(String base64Chunk) {
            try {
                if (pendingVideoStream == null) return "ERROR: Video file is not open.";
                if (base64Chunk == null || base64Chunk.length() == 0) return "OK";

                pendingBase64Carry.append(sanitizeBase64Chunk(base64Chunk));
                decodePendingBase64(false);
                return "OK";
            } catch (Exception e) {
                closePendingVideoStream();
                return "ERROR: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public synchronized String appendVideoBytes(String byteChunk) {
            try {
                if (pendingVideoStream == null) return "ERROR: Video file is not open.";
                if (byteChunk == null || byteChunk.length() == 0) return "OK";

                byte[] bytes = new byte[byteChunk.length()];
                for (int i = 0; i < byteChunk.length(); i++) {
                    bytes[i] = (byte) (byteChunk.charAt(i) & 0xFF);
                }
                pendingVideoStream.write(bytes);
                return "OK";
            } catch (Exception e) {
                closePendingVideoStream();
                return "ERROR: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public synchronized String finishVideoFile() {
            try {
                decodePendingBase64(true);
                closePendingVideoStream();
                if (pendingVideoFile == null || !pendingVideoFile.exists()) {
                    return "ERROR: Video file was not created.";
                }

                Uri uri = FileProvider.getUriForFile(
                    MainActivity.this,
                    getPackageName() + ".fileprovider",
                    pendingVideoFile
                );
                return uri.toString();
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public synchronized String saveVideo(String displayName, String mimeType) {
            try {
                Uri savedUri = savePendingVideoToGallery(displayName, mimeType);
                return savedUri.toString();
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public String shareVideo(String uriString, String title, String text, String dialogTitle) {
            try {
                Uri uri = Uri.parse(uriString);
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("video/*");
                shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                shareIntent.putExtra(Intent.EXTRA_SUBJECT, title == null ? "Urban Charades Video" : title);
                shareIntent.putExtra(Intent.EXTRA_TEXT, text == null ? "" : text);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                shareIntent.setClipData(ClipData.newUri(getContentResolver(), "Urban Charades Recording", uri));

                Intent chooser = Intent.createChooser(
                    shareIntent,
                    dialogTitle == null ? "Share recording" : dialogTitle
                );
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(chooser);
                return "OK";
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        }
    }
}
