package fr.cyril.calendrierconges;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ActivityNotFoundException;
import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.CalendarContract;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.TimeZone;

/**
 * Appli Calendrier congés : affiche la page www/index.html et lui fournit
 * (objet JavaScript « AndroidApp ») la sauvegarde dans un fichier de l'appli,
 * l'export / import d'un fichier JSON et l'impression en PDF.
 */
public class MainActivity extends Activity {
    private static final int REQ_EXPORT = 1, REQ_IMPORT = 2, REQ_VOICE = 3, REQ_CAL_PERM = 10;
    private WebView web;
    private String pendingExport;
    private String pendingCalendar;

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

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_CAL_PERM) return;
        boolean ok = results.length > 0;
        for (int r : results) ok &= r == PackageManager.PERMISSION_GRANTED;
        String p = pendingCalendar;
        pendingCalendar = null;
        if (ok && p != null) runCalendarSync(p);
        else js("calendarSyncDone(" + JSONObject.quote(err("accès à l’agenda refusé (Paramètres > Applis > Congés > Autorisations)")) + ")");
    }

    private static String err(String msg) {
        try { return new JSONObject().put("ok", false).put("error", msg).toString(); } catch (Exception e) { return "{\"ok\":false}"; }
    }

    private void runCalendarSync(String payload) {
        new Thread(() -> {
            String res;
            try { res = calendarSyncNow(new JSONObject(payload)).toString(); }
            catch (Exception e) { res = err("erreur " + e.getMessage()); }
            js("calendarSyncDone(" + JSONObject.quote(res) + ")");
        }).start();
    }

    /** Agenda choisi : celui du compte Google principal, sinon le premier agenda modifiable. */
    private long[] findCalendar(StringBuilder name) {
        String[] proj = {CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.Calendars.IS_PRIMARY,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME};
        long best = -1; int bestScore = -1; String bestName = "";
        try (Cursor c = getContentResolver().query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (c.getInt(3) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue;
                int score = ("com.google".equals(c.getString(1)) ? 2 : 0) + (c.getInt(2) == 1 ? 1 : 0);
                if (score > bestScore) { bestScore = score; best = c.getLong(0); bestName = c.getString(4); }
            }
        }
        name.append(bestName == null ? "" : bestName);
        return new long[]{best};
    }

    private boolean eventExists(ContentResolver cr, long id) {
        try (Cursor c = cr.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
                new String[]{CalendarContract.Events._ID, CalendarContract.Events.DELETED}, null, null, null)) {
            return c != null && c.moveToFirst() && c.getInt(1) == 0;
        }
    }

    private JSONObject calendarSyncNow(JSONObject in) throws Exception {
        ContentResolver cr = getContentResolver();
        StringBuilder calName = new StringBuilder();
        long calId = findCalendar(calName)[0];
        if (calId < 0) return new JSONObject(err("aucun agenda Google trouvé sur ce téléphone"));

        JSONArray rm = in.optJSONArray("remove");
        for (int i = 0; rm != null && i < rm.length(); i++)
            cr.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, rm.getLong(i)), null, null);

        JSONArray evs = in.getJSONArray("events"), ids = new JSONArray();
        String desc = in.optString("description", "");
        for (int i = 0; i < evs.length(); i++) {
            JSONObject e = evs.getJSONObject(i);
            boolean allDay = e.optBoolean("allDay");
            ContentValues v = new ContentValues();
            v.put(CalendarContract.Events.TITLE, e.getString("title"));
            v.put(CalendarContract.Events.DESCRIPTION, desc);
            v.put(CalendarContract.Events.DTSTART, e.getLong("start"));
            v.put(CalendarContract.Events.DTEND, e.getLong("end"));
            v.put(CalendarContract.Events.ALL_DAY, allDay ? 1 : 0);
            v.put(CalendarContract.Events.EVENT_TIMEZONE, allDay ? "UTC" : TimeZone.getDefault().getID());
            v.put(CalendarContract.Events.HAS_ALARM, 1);
            long id = e.optLong("id", 0);
            if (id > 0 && eventExists(cr, id)) {
                cr.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), v, null, null);
            } else {
                v.put(CalendarContract.Events.CALENDAR_ID, calId);
                Uri u = cr.insert(CalendarContract.Events.CONTENT_URI, v);
                if (u == null) return new JSONObject(err("l’agenda a refusé l’événement"));
                id = ContentUris.parseId(u);
            }
            cr.delete(CalendarContract.Reminders.CONTENT_URI, CalendarContract.Reminders.EVENT_ID + "=?", new String[]{String.valueOf(id)});
            JSONArray rem = e.optJSONArray("reminders");
            for (int k = 0; rem != null && k < rem.length(); k++) {
                ContentValues r = new ContentValues();
                r.put(CalendarContract.Reminders.EVENT_ID, id);
                r.put(CalendarContract.Reminders.MINUTES, rem.getInt(k));
                r.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);
                cr.insert(CalendarContract.Reminders.CONTENT_URI, r);
            }
            ids.put(id);
        }
        return new JSONObject().put("ok", true).put("date", in.getString("date")).put("ids", ids).put("calendar", calName.toString());
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

        /** Synchronise les rendez-vous d'un jour avec l'agenda Google du téléphone. */
        @JavascriptInterface
        public void calendarSync(String payload) {
            runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED
                        || checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                    pendingCalendar = payload;
                    requestPermissions(new String[]{Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR}, REQ_CAL_PERM);
                } else {
                    runCalendarSync(payload);
                }
            });
        }

        @JavascriptInterface
        public void copyText(String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Rendez-vous", text));
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
