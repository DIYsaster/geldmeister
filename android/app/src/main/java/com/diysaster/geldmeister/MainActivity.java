package com.diysaster.geldmeister;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 8401;
    private static final String APP_URL = "https://appassets.androidplatform.net/assets/www/index.html";
    private static final int MAX_REQUEST_BODY = 32 * 1024;
    private static final int MAX_RESPONSE_BODY = 64 * 1024;

    private WebView webView;
    private final ExecutorService budgetExecutor = Executors.newSingleThreadExecutor();
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
        webView.addJavascriptInterface(new GeldmeisterBudgetBridge(), "GeldmeisterBridge");

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
        budgetExecutor.shutdownNow();
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

    private final class GeldmeisterBudgetBridge {
        @JavascriptInterface
        public void request(String requestId, String baseUrl, String method, String path, String body, String token) {
            if (requestId == null || requestId.length() > 100) return;
            String safeBody = body == null ? "" : body;
            String safeToken = token == null ? "" : token;
            if (safeBody.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BODY) {
                deliverBudgetResponse(requestId, jsonError("Die Anfrage ist zu groß."));
                return;
            }
            if ("POST".equalsIgnoreCase(method) && "/v1/entry".equals(path)) {
                runOnUiThread(() -> confirmBudgetEntry(requestId, baseUrl, method, path, safeBody, safeToken));
                return;
            }
            dispatchBudgetRequest(requestId, baseUrl, method, path, safeBody, safeToken);
        }
    }

    private void confirmBudgetEntry(String requestId, String baseUrl, String method, String path, String body, String token) {
        try {
            JSONObject entry = new JSONObject(body);
            String rawCents = entry.optString("amount_cents", "");
            if (!rawCents.matches("[0-9]{1,15}")) throw new IOException("Der Betrag ist ungültig.");
            long cents = Long.parseLong(rawCents);
            if (cents <= 0 || cents > 100_000_000_000_000L) throw new IOException("Der Betrag liegt außerhalb des zulässigen Bereichs.");
            String date = entry.optString("date", "");
            if (!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IOException("Das Buchungsdatum ist ungültig.");
            String formattedAmount = java.text.NumberFormat.getCurrencyInstance(Locale.GERMANY)
                    .format(new java.math.BigDecimal(cents).movePointLeft(2));
            new AlertDialog.Builder(this)
                    .setTitle("Bon an Klarbudget senden?")
                    .setMessage("Geldmeister möchte eine Ausgabe über " + formattedAmount + " am " + date
                            + " anlegen. Konto und Kategorie siehst du in Geldmeister. Nur bestätigen, wenn die Buchungsdaten geprüft sind.")
                    .setNegativeButton("Abbrechen", (dialog, which) -> deliverBudgetResponse(requestId,
                            jsonError("Übertragung abgebrochen; es wurde keine Buchung angelegt.")))
                    .setPositiveButton("Ausgabe anlegen", (dialog, which) ->
                            dispatchBudgetRequest(requestId, baseUrl, method, path, body, token))
                    .setOnCancelListener(dialog -> deliverBudgetResponse(requestId,
                            jsonError("Übertragung abgebrochen; es wurde keine Buchung angelegt.")))
                    .show();
        } catch (Exception exception) {
            deliverBudgetResponse(requestId, jsonError(exception.getMessage() == null ? "Buchungsdaten sind ungültig." : exception.getMessage()));
        }
    }

    private void dispatchBudgetRequest(String requestId, String baseUrl, String method, String path, String body, String token) {
        try {
            budgetExecutor.execute(() -> {
                String result;
                try {
                    result = performBudgetRequest(baseUrl, method, path, body, token);
                } catch (Exception exception) {
                    result = jsonError(exception.getMessage() == null ? "Lokale Verbindung fehlgeschlagen." : exception.getMessage());
                }
                deliverBudgetResponse(requestId, result);
            });
        } catch (RuntimeException exception) {
            deliverBudgetResponse(requestId, jsonError("Die lokale Verbindung ist nicht verfügbar."));
        }
    }

    private void deliverBudgetResponse(String requestId, String response) {
        String idLiteral = JSONObject.quote(requestId);
        String responseLiteral = JSONObject.quote(response);
        runOnUiThread(() -> {
            if (webView == null) return;
            webView.evaluateJavascript(
                    "window.GeldmeisterNative && window.GeldmeisterNative.complete(" + idLiteral + "," + responseLiteral + ");",
                    null);
        });
    }

    private static String performBudgetRequest(String baseUrl, String method, String path, String body, String token) throws Exception {
        ConnectionTarget target = parseConnectionTarget(baseUrl);
        String normalizedMethod = method == null ? "" : method.toUpperCase(Locale.ROOT);
        boolean pairing = "POST".equals(normalizedMethod) && "/v1/pair".equals(path);
        boolean catalog = "GET".equals(normalizedMethod) && "/v1/catalog".equals(path);
        boolean entry = "POST".equals(normalizedMethod) && "/v1/entry".equals(path);
        if (!pairing && !catalog && !entry) throw new IOException("Dieser Klarbudget-Endpunkt ist nicht freigegeben.");
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BODY) throw new IOException("Die Anfrage ist zu groß.");

        if (pairing) {
            if (!token.isEmpty()) throw new IOException("Ungültige Kopplungsanfrage.");
            JSONObject data = new JSONObject(body);
            String code = data.optString("pairing_code", "");
            if (!code.matches("[0-9]{8}")) throw new IOException("Die PIN muss aus acht Ziffern bestehen.");
        } else if (!token.matches("[a-f0-9]{64}")) {
            throw new IOException("Die Handyverbindung ist abgelaufen. Bitte erneut koppeln.");
        }
        if (catalog && !body.isEmpty()) throw new IOException("Ungültige Kataloganfrage.");

        byte[] requestBody = body.getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket()) {
            // The protocol is HTTP on a private LAN; strict address and endpoint checks above keep this bridge scoped.
            socket.connect(new InetSocketAddress(target.host, target.port), 5000);
            socket.setSoTimeout(5000);
            OutputStream output = socket.getOutputStream();
            StringBuilder headers = new StringBuilder()
                    .append(normalizedMethod).append(' ').append(path).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(target.host).append(':').append(target.port).append("\r\n")
                    .append("Accept: application/json\r\n")
                    .append("Connection: close\r\n");
            if (!pairing) headers.append("Authorization: Bearer ").append(token).append("\r\n");
            if (!requestBodyIsEmpty(normalizedMethod, requestBody)) {
                headers.append("Content-Type: application/json; charset=utf-8\r\n")
                        .append("Content-Length: ").append(requestBody.length).append("\r\n");
            }
            headers.append("\r\n");
            output.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
            if (requestBody.length > 0) output.write(requestBody);
            output.flush();

            InputStream input = socket.getInputStream();
            byte[] headerBytes = readHttpHeaders(input);
            String headerText = new String(headerBytes, StandardCharsets.ISO_8859_1);
            String[] lines = headerText.split("\r\n");
            if (lines.length == 0 || !lines[0].startsWith("HTTP/1.")) throw new IOException("Klarbudget hat eine ungültige Antwort gesendet.");
            String[] statusParts = lines[0].split(" ");
            if (statusParts.length < 2) throw new IOException("Klarbudget hat eine ungültige Antwort gesendet.");
            int status = Integer.parseInt(statusParts[1]);
            int contentLength = -1;
            for (int index = 1; index < lines.length; index++) {
                int separator = lines[index].indexOf(':');
                if (separator < 0) continue;
                String name = lines[index].substring(0, separator).trim();
                String value = lines[index].substring(separator + 1).trim();
                if ("content-length".equalsIgnoreCase(name)) contentLength = Integer.parseInt(value);
                if ("transfer-encoding".equalsIgnoreCase(name)) throw new IOException("Klarbudget hat eine nicht unterstützte Antwort gesendet.");
            }
            if (contentLength < 0 || contentLength > MAX_RESPONSE_BODY) throw new IOException("Die Antwort von Klarbudget ist ungültig oder zu groß.");
            byte[] responseBody = readExact(input, contentLength);
            JSONObject envelope = new JSONObject();
            envelope.put("status", status);
            envelope.put("body", new String(responseBody, StandardCharsets.UTF_8));
            return envelope.toString();
        }
    }

    private static boolean requestBodyIsEmpty(String method, byte[] body) {
        return body.length == 0 && "GET".equals(method);
    }

    private static byte[] readHttpHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int matched = 0;
        while (buffer.size() < 8192) {
            int next = input.read();
            if (next < 0) throw new IOException("Klarbudget hat die Verbindung vor der Antwort geschlossen.");
            buffer.write(next);
            if ((matched == 0 && next == '\r') || (matched == 2 && next == '\r')) matched++;
            else if ((matched == 1 && next == '\n') || (matched == 3 && next == '\n')) matched++;
            else matched = next == '\r' ? 1 : 0;
            if (matched == 4) {
                byte[] all = buffer.toByteArray();
                byte[] header = new byte[all.length - 4];
                System.arraycopy(all, 0, header, 0, header.length);
                return header;
            }
        }
        throw new IOException("Die Antwort-Kopfzeile von Klarbudget ist zu groß.");
    }

    private static byte[] readExact(InputStream input, int length) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(bytes, offset, length - offset);
            if (count < 0) throw new IOException("Klarbudget hat die Antwort unvollständig gesendet.");
            offset += count;
        }
        return bytes;
    }

    private static ConnectionTarget parseConnectionTarget(String raw) throws Exception {
        if (raw == null || raw.length() > 100) throw new IOException("Bitte gib die PC-Adresse aus Klarbudget ein.");
        android.net.Uri uri = android.net.Uri.parse(raw.trim());
        String host = uri.getHost();
        int port = uri.getPort();
        if (!"http".equals(uri.getScheme()) || host == null || port < 1 || port > 65535
                || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))
                || uri.getQuery() != null || uri.getFragment() != null || uri.getUserInfo() != null) {
            throw new IOException("Adresse ungültig. Bitte die angezeigte http://IPv4:Port-Adresse verwenden.");
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) throw new IOException("Es sind nur private IPv4-Adressen erlaubt.");
        int[] octets = new int[4];
        for (int index = 0; index < 4; index++) {
            if (!parts[index].matches("[0-9]{1,3}")) throw new IOException("Die PC-Adresse muss eine IPv4-Adresse sein.");
            octets[index] = Integer.parseInt(parts[index]);
            if (octets[index] > 255) throw new IOException("Die PC-Adresse ist ungültig.");
        }
        String canonicalHost = octets[0] + "." + octets[1] + "." + octets[2] + "." + octets[3];
        if (!canonicalHost.equals(host)) throw new IOException("Bitte verwende die PC-Adresse genau so, wie Klarbudget sie anzeigt.");
        boolean privateAddress = octets[0] == 10
                || (octets[0] == 192 && octets[1] == 168)
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31);
        if (!privateAddress) throw new IOException("Erlaubt sind nur private LAN-Adressen (10.x, 172.16–31.x oder 192.168.x).");
        return new ConnectionTarget(host, port);
    }

    private static String jsonError(String message) {
        try {
            return new JSONObject().put("error", message).toString();
        } catch (JSONException exception) {
            return "{\"error\":\"Lokale Verbindung fehlgeschlagen.\"}";
        }
    }

    private static final class ConnectionTarget {
        final String host;
        final int port;

        ConnectionTarget(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }
}
