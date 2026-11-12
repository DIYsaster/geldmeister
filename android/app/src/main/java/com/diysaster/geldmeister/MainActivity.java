package com.diysaster.geldmeister;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;

import java.io.File;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 8401;
    private static final String APP_URL = "https://appassets.androidplatform.net/assets/www/index.html";

    private WebView webView;
    private ValueCallback<Uri[]> pendingFileCallback;
    private Uri cameraOutputUri;
    private boolean cameraCapturePending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(244, 245, 237));
        getWindow().setNavigationBarColor(Color.rgb(244, 245, 237));
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(244, 245, 237));
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.setWebViewClient(new WebViewClientCompat() {
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return assetLoader.shouldInterceptRequest(Uri.parse(url));
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView view,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams) {
                if (pendingFileCallback != null) {
                    pendingFileCallback.onReceiveValue(null);
                }
                pendingFileCallback = filePathCallback;

                if (fileChooserParams.isCaptureEnabled() && launchCamera()) {
                    return true;
                }

                cameraCapturePending = false;
                cameraOutputUri = null;
                try {
                    startActivityForResult(fileChooserParams.createIntent(), FILE_CHOOSER_REQUEST);
                    return true;
                } catch (Exception exception) {
                    finishFileSelection(null);
                    return false;
                }
            }
        });

        setContentView(webView);
        webView.loadUrl(APP_URL);
    }

    private boolean launchCamera() {
        Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (cameraIntent.resolveActivity(getPackageManager()) == null) {
            return false;
        }

        try {
            File output = new File(getCacheDir(), "receipt-" + System.currentTimeMillis() + ".jpg");
            cameraOutputUri = FileProvider.getUriForFile(
                    this, "com.diysaster.geldmeister.fileprovider", output);
            cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraOutputUri);
            cameraIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            cameraIntent.setClipData(ClipData.newUri(getContentResolver(), "Kassenbonfoto", cameraOutputUri));
            cameraCapturePending = true;
            startActivityForResult(cameraIntent, FILE_CHOOSER_REQUEST);
            return true;
        } catch (Exception exception) {
            cameraCapturePending = false;
            cameraOutputUri = null;
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST) {
            return;
        }

        Uri[] result = null;
        if (resultCode == RESULT_OK) {
            if (cameraCapturePending && cameraOutputUri != null) {
                result = new Uri[]{cameraOutputUri};
            } else {
                result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            }
        }
        finishFileSelection(result);
    }

    private void finishFileSelection(Uri[] result) {
        if (pendingFileCallback != null) {
            pendingFileCallback.onReceiveValue(result);
            pendingFileCallback = null;
        }
        cameraCapturePending = false;
        cameraOutputUri = null;
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (pendingFileCallback != null) {
            pendingFileCallback.onReceiveValue(null);
            pendingFileCallback = null;
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
