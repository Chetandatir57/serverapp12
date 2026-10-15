package dev.mccontrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;

/** A thin app around the Server Control page: its own icon and window, no browser bar, talks to the server over the phone's internet. */
public class MainActivity extends Activity {

    private static final String ERROR_HTML = "<html><body style='margin:0;background:#0b1020;color:#eaf0ff;font-family:sans-serif;"
            + "display:flex;align-items:center;justify-content:center;height:100vh;text-align:center'><div style='padding:24px'>"
            + "<h2>Cannot reach the server</h2><p style='color:#8f9bbd'>Check the phone's internet, and that the laptop is on "
            + "and the controller is running.</p>"
            + "<button style='font-size:17px;padding:14px 22px;margin:6px;border:0;border-radius:12px;background:#2f8cff;color:#fff' "
            + "onclick='AndroidApp.retry()'>Try again</button><br>"
            + "<button style='font-size:15px;padding:12px 18px;margin:6px;border:1px solid #5bd0ff;border-radius:12px;background:none;color:#9fd8ff' "
            + "onclick='AndroidApp.changeUrl()'>Change server address</button></div></body></html>";

    private WebView web;
    private SharedPreferences prefs;

    private class Bridge {
        @JavascriptInterface
        public void changeUrl() {
            runOnUiThread(() -> askUrl(true));
        }

        @JavascriptInterface
        public void retry() {
            runOnUiThread(() -> load());
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF0B1020);
        getWindow().setNavigationBarColor(0xFF0B1020);
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B1020);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0B1020);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        WebSettings st = web.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        st.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= 26) st.setSafeBrowsingEnabled(false);    // the address is your own server; free tunnel domains get flagged by Google
        web.addJavascriptInterface(new Bridge(), "AndroidApp");
        web.setWebChromeClient(new WebChromeClient());           // needed so confirm() / prompt() dialogs of the page show up
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    view.loadDataWithBaseURL(null, ERROR_HTML, "text/html", "utf-8", null);
                }
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else load();
    }

    private String address() {
        String u = prefs.getString("url", getString(R.string.default_url)).trim();
        if (!u.isEmpty() && !u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
        return u;
    }

    /** The saved value is either a full https link (old way) or a one-time "link code". The laptop publishes its current link under that code. */
    private void load() {
        final String u = prefs.getString("url", "").trim();
        if (u.isEmpty()) { askUrl(false); return; }
        if (u.startsWith("http://") || u.startsWith("https://")) { web.loadUrl(u); return; }
        new Thread(() -> {
            String link = fetchLink(u);
            if (link != null) prefs.edit().putString("last", link).apply();
            else link = prefs.getString("last", "");
            final String fl = link;
            runOnUiThread(() -> {
                if (fl.isEmpty()) web.loadDataWithBaseURL(null, ERROR_HTML, "text/html", "utf-8", null);
                else web.loadUrl(fl);
            });
        }).start();
    }

    private String fetchLink(String code) {
        try {
            java.net.URL url = new java.net.URL("https://ntfy.sh/" + java.net.URLEncoder.encode(code, "UTF-8") + "/json?poll=1&since=24h");
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) url.openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(10000);
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
            String line, last = null;
            while ((line = r.readLine()) != null) {
                try {
                    org.json.JSONObject o = new org.json.JSONObject(line);
                    if ("message".equals(o.optString("event")) && o.optString("message").startsWith("http")) last = o.getString("message").trim();
                } catch (Exception ignored) {}
            }
            r.close();
            return last;
        } catch (Exception e) { return null; }
    }

    private void askUrl(boolean cancelable) {
        final EditText box = new EditText(this);
        box.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        box.setSingleLine(true);
        String cur = prefs.getString("url", getString(R.string.default_url));
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence c = cm.getPrimaryClip().getItemAt(0).getText();
                if (c != null && c.toString().contains("trycloudflare.com")) cur = c.toString().trim();
            }
        } catch (Exception ignored) {}
        box.setText(cur);
        box.setSelection(box.getText().length());
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Link code")
                .setMessage("Enter the Link code shown in the laptop window (only once)")
                .setView(box)
                .setCancelable(cancelable)
                .setPositiveButton("Save", (d, w) -> {
                    prefs.edit().putString("url", box.getText().toString().trim()).apply();
                    load();
                });
        if (cancelable) b.setNegativeButton("Cancel", null);
        b.show();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
