package fr.cyril.calendrierconges;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.speech.RecognizerIntent;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Appli Calendrier congés : affiche la page www/index.html et lui fournit
 * (objet JavaScript « AndroidApp ») la sauvegarde dans un fichier de l'appli,
 * l'export / import d'un fichier JSON et l'impression en PDF.
 */
public class MainActivity extends Activity {
    private static final int REQ_EXPORT = 1, REQ_IMPORT = 2, REQ_VOICE = 3;
    private WebView web;
    private String pendingExport;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        web.setBackgroundColor(night ? Color.parseColor("#0f0e0d") : Color.parseColor("#f6f1e7"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "AndroidApp");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        // La page ferme d'abord la fenêtre de saisie ou la sélection
        web.evaluateJavascript("(window.appBack && appBack()) ? 'oui' : 'non'", v -> {
            if (v == null || !v.contains("oui")) finish();
        });
    }

    private File stateFile() { return new File(getFilesDir(), "calendrier.json"); }

    private void js(String code) { runOnUiThread(() -> web.evaluateJavascript(code, null)); }

    private class Bridge {
        @JavascriptInterface
        public boolean saveState(String json) {
            try {
                File f = stateFile();
                if (json == null || json.isEmpty()) { f.delete(); return true; }
                File tmp = new File(getFilesDir(), "calendrier.json.tmp");
                try (OutputStream o = new FileOutputStream(tmp)) { o.write(json.getBytes(StandardCharsets.UTF_8)); }
                return tmp.renameTo(f);
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public String loadState() {
            try {
                File f = stateFile();
                if (!f.exists()) return "";
                try (InputStream in = new FileInputStream(f)) { return read(in); }
            } catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public void exportFile(String name, String content) {
            pendingExport = content;
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                i.putExtra(Intent.EXTRA_TITLE, name);
                startActivityForResult(i, REQ_EXPORT);
            });
        }

        @JavascriptInterface
        public void importFile() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, REQ_IMPORT);
            });
        }

        /** Dictée d'un rendez-vous : fenêtre de reconnaissance vocale d'Android (français). */
        @JavascriptInterface
        public void startVoice() {
            runOnUiThread(() -> {
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR");
                i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Ex : rendez-vous dentiste mardi à 14 h 30");
                i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                try { startActivityForResult(i, REQ_VOICE); }
                catch (ActivityNotFoundException e) { js("voiceError('Reconnaissance vocale absente : installe ou active l’appli Google.')"); }
            });
        }

        @JavascriptInterface
        public void print() {
            runOnUiThread(() -> {
                PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                String title = "Calendrier congés";
                pm.print(title, web.createPrintDocumentAdapter(title),
                        new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape()).build());
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
        if (requestCode == REQ_EXPORT) {
            boolean ok = false;
            if (uri != null && pendingExport != null) {
                try (OutputStream o = getContentResolver().openOutputStream(uri, "wt")) {
                    o.write(pendingExport.getBytes(StandardCharsets.UTF_8));
                    ok = true;
                } catch (Exception ignored) { }
            }
            pendingExport = null;
            js("exportDone(" + ok + ")");
        } else if (requestCode == REQ_VOICE) {
            ArrayList<String> r = (resultCode == RESULT_OK && data != null) ? data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) : null;
            if (r != null && !r.isEmpty()) js("onVoiceResult(" + JSONObject.quote(r.get(0)) + ")");
            else js("voiceError('')");
        } else if (requestCode == REQ_IMPORT && uri != null) {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                js("importText(" + JSONObject.quote(read(in)) + ")");
            } catch (Exception e) {
                js("toastMsg('Impossible de lire ce fichier.')");
            }
        }
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }
}
