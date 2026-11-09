package com.mahmuduls.metermanager;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.content.SharedPreferences;

import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;


import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.database.Cursor;

public class MainActivity extends Activity {
    private WebView webView;
    private static final String TAG = "MeterManager";
    private static final String WEB_URL = "https://mhasanbogura.github.io/metermanager/";
    private static final String DESCO_PORTAL = "https://prepaid.desco.org.bd";
    private static final String PANEL = "https://customer.nesco.gov.bd/pre/panel";
    private static final String SUBMIT_HISTORY = "\u09B0\u09BF\u099A\u09BE\u09B0\u09CD\u099C \u09B9\u09BF\u09B8\u09CD\u099F\u09CD\u09B0\u09BF";
    private static final String SUBMIT_MONTHLY = "\u09AE\u09BE\u09B8\u09BF\u0995 \u09AC\u09CD\u09AF\u09AC\u09B9\u09BE\u09B0";
    private static final int RC_SIGN_IN = 9001;
    private static final String PREFS_NAME = "MeterManagerPrefs";
    private static final String KEY_PENDING_GOOGLE_TOKEN = "pendingGoogleToken";
    private static final String KEY_PENDING_GOOGLE_EMAIL = "pendingGoogleEmail";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String pendingSaveContent;
    private GoogleSignInClient googleSignInClient;
    private long pendingDownloadId = -1;
    private String pendingApkPath;
    private BroadcastReceiver updateReceiver;

    /* ---------- Google Drive app-store scanning (same system as App Store) ----------
       The release APKs are published to a shared Google Drive folder. The folder is
       world readable, so the public Drive REST API only needs an API key - no OAuth
       consent is involved. This mirrors what the App Store app does when it scans
       its "Android" subfolder for newer builds. */
    private static final String DRIVE_API_KEY = "AIzaSyAX7T6Vd75LnhQg15IydOLEYqjfGUT8TO8";
    private static final String DRIVE_ROOT_FOLDER_ID = "1PBrhSIvDk0QrgNS6XeTeA1RDLFPeTqKV";
    private static final String DRIVE_ANDROID_SUBFOLDER = "Android";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String savedTheme = getSharedPreferences("MeterManager", MODE_PRIVATE).getString("theme", "light");
        String resolved = savedTheme;
        if ("system".equals(savedTheme)) {
            int nightMask = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            resolved = (nightMask == android.content.res.Configuration.UI_MODE_NIGHT_YES) ? "oled" : "light";
        }
        setStatusBarColorDirect("light".equals(resolved) ? "#f2f4f8" : "oled".equals(resolved) ? "#000000" : "#0f1218");

        getWindow().setDecorFitsSystemWindows(true);

        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        
        // Restore persisted pendingDownloadId
        pendingDownloadId = getSharedPreferences("MeterManager", MODE_PRIVATE).getLong("pendingDownloadId", -1);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        settings.setAllowContentAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webView.setBackgroundColor(0xFF000000);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setUserAgentString("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

        webView.addJavascriptInterface(new NescoBridge(), "NescoBridge");

        /* Check if a download finished while the app was closed. */
        checkPendingDownload();

        /* Remove any APK left behind by a previous update so the next download
           starts from a clean cache. */
        sweepDownloadedApks();

        /* requestServerAuthCode(..., true) makes Google re-prompt its own consent
           screen ("You're signing in to Meter Manager" + privacy policy / ToS) after
           the account is chosen, instead of silently accepting a previously granted
           scope. This is the same option set the Mess Manager app uses. The auth code
           itself is unused — we only need the consent step. */
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id))
                .requestServerAuthCode(getString(R.string.default_web_client_id), true)
                .requestEmail()
                .build();
        googleSignInClient = GoogleSignIn.getClient(this, gso);

        /* Receiver that fires when the DownloadManager finishes downloading the
           Meter Manager update APK, then triggers silent installation. */
        updateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                    long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    if (id == -1) {
                        id = getSharedPreferences("MeterManager", MODE_PRIVATE).getLong("pendingDownloadId", -1);
                    }
                    if (id == pendingDownloadId && id > 0) {
                        installApk(id);
                        pendingDownloadId = -1;
                        getSharedPreferences("MeterManager", MODE_PRIVATE).edit().remove("pendingDownloadId").apply();
                    }
                }
            }
        };
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(updateReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        }

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
                Log.d("WebViewConsole", consoleMessage.message() + " -- From line " +
                        consoleMessage.lineNumber() + " of " + consoleMessage.sourceId());
                return true;
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                WebView popup = new WebView(MainActivity.this);
                popup.getSettings().setJavaScriptEnabled(true);
                popup.getSettings().setDomStorageEnabled(true);
                popup.getSettings().setUserAgentString(settings.getUserAgentString());
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                popup.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, String url) {
                        return false;
                    }
                });
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && url.startsWith(DESCO_PORTAL)) {
                    // Trigger cookie capture after user logs in on the DESCO portal
                    view.evaluateJavascript("NescoBridge.saveDescoCookies();", null);
                    // "Add account" flow: a fresh DESCO_SESSION means login done.
                    if (addingDescoAccount) maybeFinishDescoAdd(view);
                }
                injectOverrides();
                if (url != null && url.startsWith(WEB_URL)) {
                    if (pendingDescoNotify != null) {
                        // Back from the portal with a newly captured account — tell JS.
                        String payload = pendingDescoNotify;
                        pendingDescoNotify = null;
                        addingDescoAccount = false;
                        view.evaluateJavascript(
                            "window.__descoAccountAdded&&window.__descoAccountAdded(" + payload + ")", null);
                    } else {
                        // Normal app (re)load: make sure the active account's
                        // session is in the cookie jar before API calls go out.
                        restoreActiveDescoCookies();
                    }
                }
                view.evaluateJavascript(
                    "(function(){var t=document.querySelector('meta[name=theme-color]');return t?t.content:'light'})()",
                    value -> {
                        String color = value != null ? value.replace("\"", "") : "light";
                        if ("light".equals(color)) color = "#f2f4f8";
                        else if ("dark".equals(color)) color = "#0f1218";
                        else if ("oled".equals(color)) color = "#000000";
                        setStatusBarColorDirect(color);
                    });
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                String lower = url.toLowerCase();
                if (lower.startsWith("http://") || lower.startsWith("https://")) {
                    if (lower.contains("wa.me/") || lower.contains("api.whatsapp.com") ||
                        lower.contains("m.me/") || lower.contains("fb-messenger://") ||
                        lower.contains("instagram.com/") || lower.contains("instagram://") ||
                        lower.contains("t.me/") || lower.contains("tg://") ||
                        lower.contains("twitter.com/") || lower.contains("x.com/") ||
                        lower.contains("facebook.com/") || lower.contains("fb.com/") ||
                        lower.contains("linkedin.com/") || lower.contains("youtube.com/") ||
                        lower.contains("github.com/")) {
                        try {
                            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                            startActivity(intent);
                        } catch (Exception e) {
                            // fallback: let WebView handle it
                        }
                        return true;
                    }
                } else if (!lower.startsWith("javascript:")) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        startActivity(intent);
                    } catch (Exception e) {
                        // ignore
                    }
                    return true;
                }
                return false;
            }
        });

        webView.loadUrl(WEB_URL);
    }

    private void injectOverrides() {
        String js =
            "(function(){" +
            "  if(window.__mm_injected) return;" +
            "  window.__mm_injected=true;" +
            "  var backBtn=document.createElement('div');" +
            "  backBtn.innerHTML='<div id=\"mm-back-btn\" style=\"position:fixed;top:10px;left:10px;z-index:9999;background:#0b3d91;color:white;padding:10px 15px;border-radius:20px;font-weight:600;box-shadow:0 4px 6px rgba(0,0,0,0.3);cursor:pointer;font-size:14px\">← Back to Meter Manager</div>';" +
            "  var btnEl = backBtn.firstChild;" +
            "  btnEl.onclick=function(){ NescoBridge.saveDescoCookies(); if(window.maybeFinishDescoAdd) window.maybeFinishDescoAdd(); NescoBridge.closeDescoLogin(); };" +
            "  document.body.appendChild(btnEl);" +
            "  var origFetch=window.fetch;" +
            "  window.fetch=function(url,opts){" +
            "    if(typeof url==='string' && url.indexOf('/nesco')>=0){" +
            "      try{" +
            "        var u=new URL(url,location.href);" +
            "        var probe=u.searchParams.get('probe');" +
            "        var cust=u.searchParams.get('cust');" +
            "        if(probe==='1') return Promise.resolve(new Response(JSON.stringify({ok:true,probe:true}),{status:200,headers:{'Content-Type':'application/json'}}));" +
            "        if(cust){" +
            "          var result=NescoBridge.nescoLookupSync(cust);" +
            "          return Promise.resolve(new Response(result,{status:200,headers:{'Content-Type':'application/json'}}));" +
            "        }" +
            "      }catch(e){}" +
            "    }" +
            "    return origFetch.call(this,url,opts);" +
            "};" +
            "})();";
        webView.evaluateJavascript(js, null);
    }

    @Override
    public void onBackPressed() {
        String curUrl = webView.getUrl();
        if (addingDescoAccount && curUrl != null && curUrl.startsWith(DESCO_PORTAL)) {
            // User backed out of the "Add account" portal login — return to the
            // app; onPageFinished restores the active account's cookies.
            addingDescoAccount = false;
            webView.loadUrl(WEB_URL);
            return;
        }
        webView.evaluateJavascript(
            "(function(){" +
            "  var d=document.getElementById('dlg');" +
            "  if(d && !d.hidden){" +
            "    if(typeof state!=='undefined' && state.updatingForUpdate) return 'dialog';" +
            "    if(typeof closeDialog==='function') closeDialog();" +
            "    return 'dialog';" +
            "  }" +
            "  var ls=document.getElementById('auth-login-screen');" +
            "  var rs=document.getElementById('auth-register-screen');" +
            "  var fs=document.getElementById('auth-forgot-screen');" +
            "  var ws=document.getElementById('auth-screen');" +
            "  if(ls && ls.style.display!=='none'){" +
            "    if(typeof showAuthScreen==='function') showAuthScreen('auth-screen');" +
            "    return 'auth';" +
            "  }" +
            "  if(rs && rs.style.display!=='none'){" +
            "    if(typeof showAuthScreen==='function') showAuthScreen('auth-screen');" +
            "    return 'auth';" +
            "  }" +
            "  if(fs && fs.style.display!=='none'){" +
            "    if(typeof showAuthScreen==='function') showAuthScreen('auth-login-screen');" +
            "    return 'auth';" +
            "  }" +
            "  if(typeof currentView!=='undefined' && currentView!=='home'){" +
            "    currentMeterId=null; showView('home');" +
            "    return 'handled';" +
            "  }" +
            "  return 'exit';" +
            "})()",
            value -> {
                String v = value != null ? value.replace("\"", "") : "exit";
                if ("exit".equals(v)) {
                    finish();
                }
            }
        );
    }

    private String nescoLookupImpl(String cust) {
        try {
            cust = cust.replaceAll("\\D", "");
            if (cust.length() > 11) cust = cust.substring(0, 11);
            if (cust.isEmpty()) return "{\"ok\":false,\"error\":\"missing customer number\"}";

            HttpsResult panelGet = httpGet(PANEL);
            if (panelGet.code != 200) return "{\"ok\":false,\"error\":\"portal HTTP " + panelGet.code + "\"}";

            String token = extractToken(panelGet.body);
            if (token == null) return "{\"ok\":false,\"error\":\"no csrf token\"}";

            String cookie = sessionCookie(panelGet.setCookie);
            if (cookie == null || cookie.isEmpty()) return "{\"ok\":false,\"error\":\"no session cookie\"}";

            String form = "_token=" + URLEncoder.encode(token, "UTF-8") +
                "&cust_no=" + URLEncoder.encode(cust, "UTF-8") +
                "&submit=" + URLEncoder.encode(SUBMIT_HISTORY, "UTF-8");

            HttpsResult postResult = httpPost(PANEL, cookie, form);
            if (postResult.code != 200) return "{\"ok\":false,\"error\":\"portal HTTP " + postResult.code + "\"}";

            String result = parseNescoHtml(postResult.body);

            // Fetch monthly usage with a fresh GET + POST
            try {
                HttpsResult panelGet2 = httpGet(PANEL);
                if (panelGet2.code == 200) {
                    String token2 = extractToken(panelGet2.body);
                    String cookie2 = sessionCookie(panelGet2.setCookie);
                    if (token2 != null && cookie2 != null && !cookie2.isEmpty()) {
                        String form2 = "_token=" + URLEncoder.encode(token2, "UTF-8") +
                            "&cust_no=" + URLEncoder.encode(cust, "UTF-8") +
                            "&submit=" + URLEncoder.encode(SUBMIT_MONTHLY, "UTF-8");
                        HttpsResult monthlyPost = httpPost(PANEL, cookie2, form2);
                        if (monthlyPost.code == 200) {
                            String monthlyJson = parseMonthlyUsageHtml(monthlyPost.body);
                            if (!monthlyJson.equals("[]")) {
                                result = result.replaceFirst("\\}$", ",\"monthlyUsage\":" + monthlyJson + "}");
                            }
                        }
                    }
                }
            } catch (Exception e2) {
                Log.w(TAG, "monthly usage fetch failed", e2);
            }

            return result;

        } catch (Exception e) {
            Log.e(TAG, "nescoLookupSync error", e);
            return "{\"ok\":false,\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    private String extractToken(String html) {
        Matcher m = Pattern.compile("name=\"_token\"\\s+value=\"([^\"]+)\"").matcher(html);
        if (m.find()) return m.group(1);
        m = Pattern.compile("_token\"\\s+value=\"([^\"]+)\"").matcher(html);
        if (m.find()) return m.group(1);
        return null;
    }

    private String sessionCookie(String setCookie) {
        if (setCookie == null || setCookie.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String line : setCookie.split("\n")) {
            String trimmed = line.trim();
            int semi = trimmed.indexOf(';');
            if (semi > 0) trimmed = trimmed.substring(0, semi);
            if (sb.length() > 0) sb.append("; ");
            sb.append(trimmed);
        }
        return sb.toString();
    }

    private String parseNescoHtml(String html) {
        int start = html.indexOf("id=\"con_info_div\"");
        if (start < 0) {
            int alt = html.indexOf("con_info_div");
            if (alt >= 0) start = alt;
        }
        if (start < 0) return "{\"ok\":false,\"error\":\"customer data not found\"}";

        int end = html.indexOf("consumerRechargeData", start);
        if (end < 0) end = html.indexOf("arrear_notice_div", start);
        if (end < 0) end = html.indexOf("class=\"row\"", start + 100);
        if (end < 0) end = Math.min(start + 5000, html.length());
        String seg = html.substring(start, end);

        Pattern inputPat = Pattern.compile("<input[^>]*(?:readonly|disabled)[^>]*value=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
        Matcher inputMat = inputPat.matcher(seg);
        java.util.List<String> inputs = new java.util.ArrayList<>();
        while (inputMat.find()) {
            inputs.add(inputMat.group(1).trim());
        }

        if (inputs.isEmpty()) {
            Pattern anyInput = Pattern.compile("<input[^>]*value=\"([^\"]+)\"");
            inputMat = anyInput.matcher(seg);
            while (inputMat.find()) {
                String val = inputMat.group(1).trim();
                if (!val.isEmpty() && !val.startsWith("#")) inputs.add(val);
            }
        }

        StringBuilder info = new StringBuilder();
        info.append("{");
        info.append("\"name\":\"").append(esc(take(inputs, 0))).append("\"");
        info.append(",\"fatherOrHusband\":\"").append(esc(take(inputs, 1))).append("\"");
        info.append(",\"address\":\"").append(esc(take(inputs, 2))).append("\"");
        info.append(",\"mobile\":\"").append(esc(take(inputs, 3))).append("\"");
        info.append(",\"office\":\"").append(esc(take(inputs, 4))).append("\"");
        info.append(",\"feeder\":\"").append(esc(take(inputs, 5))).append("\"");
        info.append(",\"consumerNo\":\"").append(esc(take(inputs, 6))).append("\"");
        info.append(",\"meterNo\":\"").append(esc(take(inputs, 7))).append("\"");
        info.append(",\"sanctionedLoad\":\"").append(esc(take(inputs, 8))).append("\"");
        info.append(",\"tariff\":\"").append(esc(take(inputs, 9))).append("\"");
        info.append(",\"meterType\":\"").append(esc(take(inputs, 10))).append("\"");
        info.append(",\"meterStatus\":\"").append(esc(take(inputs, 11))).append("\"");
        info.append(",\"installDate\":\"").append(esc(take(inputs, 12))).append("\"");
        info.append(",\"minimumRecharge\":\"").append(esc(take(inputs, 13))).append("\"");
        info.append(",\"balance\":\"").append(esc(take(inputs, 14))).append("\"");

        String lastReading = "";
        String readingDate = "";
        Pattern lrPat = Pattern.compile("last\\s*reading[^<]*?<[^>]*>([\\d.,]+)", Pattern.CASE_INSENSITIVE);
        Matcher lrMat = lrPat.matcher(html);
        if (lrMat.find()) lastReading = lrMat.group(1).trim();
        if (lastReading.isEmpty()) {
            Pattern lrPat2 = Pattern.compile("meter\\s*reading[^<]*?<[^>]*>([\\d.,]+)", Pattern.CASE_INSENSITIVE);
            Matcher lrMat2 = lrPat2.matcher(html);
            if (lrMat2.find()) lastReading = lrMat2.group(1).trim();
        }
        if (lastReading.isEmpty() && inputs.size() > 15) {
            lastReading = take(inputs, 15);
        }

        Pattern rdPat = Pattern.compile("reading[^<]*?\\b(\\d{1,2}[\\-\\/](?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[\\-\\/]\\d{4})", Pattern.CASE_INSENSITIVE);
        Matcher rdMat = rdPat.matcher(html);
        if (rdMat.find()) readingDate = rdMat.group(1).trim();
        if (readingDate.isEmpty()) {
            Pattern rdPat2 = Pattern.compile("reading[^<]*?\\b(\\d{1,2}[\\-\\/]\\d{1,2}[\\-\\/]\\d{4})", Pattern.CASE_INSENSITIVE);
            Matcher rdMat2 = rdPat2.matcher(html);
            if (rdMat2.find()) readingDate = rdMat2.group(1).trim();
        }
        if (readingDate.isEmpty()) {
            Pattern rdPat3 = Pattern.compile("\\b(\\d{1,2}\\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+\\d{4})", Pattern.CASE_INSENSITIVE);
            Matcher rdMat3 = rdPat3.matcher(seg);
            if (rdMat3.find()) readingDate = rdMat3.group(1).trim();
        }
        if (readingDate.isEmpty()) {
            Pattern rdPat4 = Pattern.compile("reading[^<]*?\\b(\\d{1,2}\\s+(?:January|February|March|April|May|June|July|August|September|October|November|December)\\s+\\d{4})", Pattern.CASE_INSENSITIVE);
            Matcher rdMat4 = rdPat4.matcher(html);
            if (rdMat4.find()) readingDate = rdMat4.group(1).trim();
        }
        if (readingDate.isEmpty()) {
            Pattern rdPat5 = Pattern.compile("\\b(\\d{1,2}\\s+(?:January|February|March|April|May|June|July|August|September|October|November|December)\\s+\\d{4})", Pattern.CASE_INSENSITIVE);
            Matcher rdMat5 = rdPat5.matcher(seg);
            if (rdMat5.find()) readingDate = rdMat5.group(1).trim();
        }
        if (readingDate.isEmpty()) {
            Pattern rdPat6 = Pattern.compile("reading[^<]{0,50}?(\\d{1,2}\\s+\\w+\\s+\\d{4})", Pattern.CASE_INSENSITIVE);
            Matcher rdMat6 = rdPat6.matcher(html);
            if (rdMat6.find()) readingDate = rdMat6.group(1).trim();
        }
        if (readingDate.isEmpty()) {
            Pattern rdPat7 = Pattern.compile("last[^<]{0,30}?reading[^<]{0,30}?>([^<]*\\d{1,2}[^<]*\\d{4}[^<]*)<", Pattern.CASE_INSENSITIVE);
            Matcher rdMat7 = rdPat7.matcher(html);
            if (rdMat7.find()) {
                String txt = rdMat7.group(1).trim();
                Matcher dateExtract = Pattern.compile("(\\d{1,2}[\\s\\-\\/]+\\w+[\\s\\-\\/]+\\d{4})").matcher(txt);
                if (dateExtract.find()) readingDate = dateExtract.group(1).trim();
            }
        }
        if (readingDate.isEmpty()) {
            Pattern rdPat8 = Pattern.compile(">\\s*([^<]*\\d{1,2}\\s+\\w+\\s+\\d{4}[^<]*)\\s*<", Pattern.CASE_INSENSITIVE);
            Matcher rdMat8 = rdPat8.matcher(seg);
            while (rdMat8.find()) {
                String txt = rdMat8.group(1).trim();
                if (txt.length() < 30 && Pattern.compile("\\d{1,2}\\s+\\w+\\s+\\d{4}").matcher(txt).find()) {
                    readingDate = txt;
                    break;
                }
            }
        }

        info.append(",\"lastReading\":\"").append(esc(lastReading)).append("\"");
        info.append(",\"readingDate\":\"").append(esc(readingDate)).append("\"");
        info.append("}");

        Pattern rowPat = Pattern.compile("<[^>]*?consumerRechargeData[^>]*>");
        Matcher rowMat = rowPat.matcher(html);
        StringBuilder hist = new StringBuilder();
        hist.append("[");
        boolean first = true;
        String firstCustomerName = "";
        String firstMeterNo = "";
        String firstTariff = "";
        String firstOrg = "";
        String[] keys = {"order", "token", "seq", "rent", "demandcharge", "tax", "pfc", "subsidyamount",
            "purchaseamount", "totalamount", "purchaseenergy", "salename", "purchasedate",
            "debtamount", "paidamount", "meterno", "customerno", "customername", "tariff", "organization"};
        String[] outKeys = {"orderId", "tokenNo", "seqNo", "meterRent", "demandCharge", "vat", "pfcCharge",
            "subsidy", "electricityAmount", "rechargeAmount", "energyUnit", "method", "rechargeDate",
            "debtAmount", "paidAmount", "meterNo", "customerNo", "customerName", "tariff", "organization"};
        while (rowMat.find()) {
            String tag = rowMat.group();
            if (!first) hist.append(",");
            first = false;
            hist.append("{");
            for (int k = 0; k < keys.length; k++) {
                if (k > 0) hist.append(",");
                String val = attr(tag, "data-" + keys[k]);
                hist.append("\"").append(outKeys[k]).append("\":\"").append(esc(val)).append("\"");
            }
            // Remote recharge status lives in the last <td> of the row (Success/Failed)
            String rechargeStatus = "";
            try {
                int cellStart = rowMat.end();
                int rowEnd = html.indexOf("</tr>", cellStart);
                if (rowEnd < 0 || rowEnd - cellStart > 20000) rowEnd = Math.min(cellStart + 20000, html.length());
                Matcher tdMat = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                    .matcher(html.substring(cellStart, rowEnd));
                while (tdMat.find()) {
                    String cell = tdMat.group(1).replaceAll("<[^>]*>", "").replace("&nbsp;", " ").trim();
                    if (!cell.isEmpty()) rechargeStatus = cell;
                }
                if (rechargeStatus.length() > 30) rechargeStatus = "";
            } catch (Exception se) {
                rechargeStatus = "";
            }
            hist.append(",\"rechargeStatus\":\"").append(esc(rechargeStatus)).append("\"");
            hist.append("}");
            if (firstCustomerName.isEmpty()) firstCustomerName = attr(tag, "data-customername");
            if (firstMeterNo.isEmpty()) firstMeterNo = attr(tag, "data-meterno");
            if (firstTariff.isEmpty()) firstTariff = attr(tag, "data-tariff");
            if (firstOrg.isEmpty()) firstOrg = attr(tag, "data-organization");
        }
        hist.append("]");

        String infoStr = info.toString();
        String infoBalance = take(inputs, 14);

        if (infoBalance.isEmpty() || "0".equals(infoBalance)) {
            Pattern balPat = Pattern.compile("balance[\"']?\\s*[>:]+\\s*[৳Tk]*\\s*(-?[\\d,.]+)", Pattern.CASE_INSENSITIVE);
            Matcher balMat = balPat.matcher(html);
            if (balMat.find()) infoBalance = balMat.group(1).trim();
        }
        if (infoBalance.isEmpty() || "0".equals(infoBalance)) {
            Pattern balPat2 = Pattern.compile("id=\"status_label\"[^>]*>([^<]*)", Pattern.CASE_INSENSITIVE);
            Matcher balMat2 = balPat2.matcher(html);
            if (balMat2.find()) {
                String t = balMat2.group(1).replaceAll("[^\\d.]", "").trim();
                if (!t.isEmpty()) infoBalance = t;
            }
        }

        if (!infoBalance.isEmpty()) {
            infoStr = infoStr.replaceFirst("\"balance\":\"[^\"]*\"", "\"balance\":\"" + esc(infoBalance) + "\"");
        }

        if (firstCustomerName.isEmpty() && !firstMeterNo.isEmpty()) {
            infoStr = infoStr.replaceFirst("\"name\":\"\"", "\"name\":\"" + esc(firstMeterNo) + "\"");
        }
        if (infoStr.contains("\"customerName\":\"\"") && !firstCustomerName.isEmpty()) {
            infoStr = infoStr.replaceFirst("\"name\":\"\"", "\"name\":\"" + esc(firstCustomerName) + "\"");
        }
        if (infoStr.contains("\"tariff\":\"\"") && !firstTariff.isEmpty()) {
            infoStr = infoStr.replaceFirst("\"tariff\":\"\"", "\"tariff\":\"" + esc(firstTariff) + "\"");
        }
        if (infoStr.contains("\"meterNo\":\"\"") && !firstMeterNo.isEmpty()) {
            infoStr = infoStr.replaceFirst("\"meterNo\":\"\"", "\"meterNo\":\"" + esc(firstMeterNo) + "\"");
        }

        return "{\"ok\":true,\"info\":" + infoStr + ",\"history\":" + hist.toString() + "}";
    }

    private String attr(String tag, String attrName) {
        Pattern p = Pattern.compile(attrName + "=['\"]([^'\"]*)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(tag);
        return m.find() ? m.group(1).trim() : "";
    }

    private String parseMonthlyUsageHtml(String html) {
        String[][] monthMap = {
            {"january","01"}, {"february","02"}, {"march","03"}, {"april","04"},
            {"may","05"}, {"june","06"}, {"july","07"}, {"august","08"},
            {"september","09"}, {"october","10"}, {"november","11"}, {"december","12"},
            {"jan","01"}, {"feb","02"}, {"mar","03"}, {"apr","04"},
            {"jun","06"}, {"jul","07"}, {"aug","08"}, {"sep","09"}, {"oct","10"}, {"nov","11"}, {"dec","12"},
            {"\u099C\u09BE\u09A8\u09C1\u09AF\u09BC\u09BE\u09B0\u09BF","01"}, // জানুয়ারি
            {"\u09AB\u09C7\u09AC\u09CD\u09B0\u09C1\u09AF\u09BC\u09BE\u09B0\u09BF","02"}, // ফেব্রুয়ারি
            {"\u09AE\u09BE\u09B0\u09CD\u099A","03"}, // মার্চ
            {"\u098F\u09AA\u09CD\u09B0\u09BF\u09B2","04"}, // এপ্রিল
            {"\u09AE\u09C7","05"}, // মে
            {"\u099C\u09C1\u09A8","06"}, // জুন
            {"\u099C\u09C1\u09B2\u09BE\u0987","07"}, // জুলাই
            {"\u0986\u0997\u09B8\u09CD\u099F","08"}, // আগস্ট
            {"\u09B8\u09C7\u09AA\u09CD\u099F\u09C7\u09AE\u09CD\u09AC\u09B0","09"}, // সেপ্টেম্বর
            {"\u0985\u0995\u09CD\u099F\u09CB\u09AC\u09B0","10"}, // অক্টোবর
            {"\u09A8\u09AD\u09C7\u09AE\u09CD\u09AC\u09B0","11"}, // নভেম্বর
            {"\u09A1\u09BF\u09B8\u09C7\u09AE\u09CD\u09AC\u09B0","12"}, // ডিসেম্বর
        };

        // Find the units column index from <th> headers
        int unitColIdx = -1;
        Matcher thMat = Pattern.compile("<thead[^>]*>([\\s\\S]*?)</thead>", Pattern.CASE_INSENSITIVE).matcher(html);
        if (thMat.find()) {
            Matcher thCell = Pattern.compile("<th[^>]*>([\\s\\S]*?)</th>", Pattern.CASE_INSENSITIVE).matcher(thMat.group(1));
            int idx = 0;
            while (thCell.find()) {
                String thText = thCell.group(1).replaceAll("<[^>]*>", "").trim();
                if (thText.contains("\u0995\u09BF.\u0993.\u0986")) { unitColIdx = idx; break; }
                idx++;
            }
        }

        StringBuilder rows = new StringBuilder();
        rows.append("[");
        boolean firstRow = true;

        Pattern trPat = Pattern.compile("<tr[^>]*>([\\s\\S]*?)</tr>", Pattern.CASE_INSENSITIVE);
        Matcher trMat = trPat.matcher(html);
        while (trMat.find()) {
            String trContent = trMat.group(1);
            Pattern tdPat = Pattern.compile("<td[^>]*>([\\s\\S]*?)</td>", Pattern.CASE_INSENSITIVE);
            Matcher tdMat = tdPat.matcher(trContent);
            java.util.List<String> tds = new java.util.ArrayList<>();
            while (tdMat.find()) {
                tds.add(tdMat.group(1).replaceAll("<[^>]*>", "").trim());
            }
            if (tds.size() < 5) continue;

            String month = null;
            String year = null;
            for (String td : tds) {
                String lower = td.toLowerCase().trim();
                for (String[] entry : monthMap) {
                    if (lower.equals(entry[0]) || lower.contains(entry[0])) {
                        month = entry[1];
                        break;
                    }
                }
                if (month != null) break;
            }
            if (month == null) continue;

            for (String td : tds) {
                if (td.trim().matches("^\\d{4}$")) {
                    year = td.trim();
                    break;
                }
            }
            if (year == null || month == null) continue;

            java.util.List<Double> nums = new java.util.ArrayList<>();
            for (String td : tds) {
                try {
                    double n = Double.parseDouble(td.replace(",", ""));
                    nums.add(n);
                } catch (Exception e) { /* skip */ }
            }
            if (nums.size() < 3) continue;

            double unitVal = 0;
            if (unitColIdx >= 0 && unitColIdx < tds.size()) {
                try { unitVal = Double.parseDouble(tds.get(unitColIdx).replace(",", "")); } catch (Exception e) { unitVal = 0; }
            } else {
                unitVal = nums.get(nums.size() - 1);
            }

            if (!firstRow) rows.append(",");
            firstRow = false;
            rows.append("{\"key\":\"").append(year).append("-").append(month).append("\",\"nums\":[");
            for (int i = 0; i < nums.size(); i++) {
                if (i > 0) rows.append(",");
                rows.append(nums.get(i));
            }
            rows.append("],\"unit\":").append(unitVal).append("}");
        }
        rows.append("]");
        return rows.toString();
    }

    private String take(java.util.List<String> list, int i) { return i < list.size() ? list.get(i) : ""; }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private void setStatusBarColorDirect(String colorHex) {
        try {
            Window window = getWindow();
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            int color = android.graphics.Color.parseColor(colorHex);
            window.setStatusBarColor(color);
            window.setNavigationBarColor(color);
            boolean light = android.graphics.Color.luminance(color) > 0.5;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    controller.setSystemBarsAppearance(
                        light ? WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS : 0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
                    controller.setSystemBarsAppearance(
                        light ? WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0,
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                int flags = window.getDecorView().getSystemUiVisibility();
                if (light) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                } else {
                    flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
                }
                window.getDecorView().setSystemUiVisibility(flags);
            }
        } catch (Exception e) {
            Log.e(TAG, "setStatusBarColorDirect error", e);
        }
    }

    private HttpsResult httpGet(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        conn.setRequestProperty("Accept", "*/*");
        conn.setInstanceFollowRedirects(false);

        int code = conn.getResponseCode();
        String setCookie = conn.getHeaderField("Set-Cookie");
        BufferedReader br = new BufferedReader(new InputStreamReader(
            code >= 400 ? conn.getErrorStream() : conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        conn.disconnect();

        return new HttpsResult(code, sb.toString(), setCookie);
    }

    private HttpsResult httpPost(String urlStr, String cookie, String body) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setDoOutput(true);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Cookie", cookie);
        conn.setRequestProperty("Origin", "https://customer.nesco.gov.bd");
        conn.setRequestProperty("Referer", urlStr);
        conn.setInstanceFollowRedirects(false);

        OutputStream os = conn.getOutputStream();
        os.write(body.getBytes(StandardCharsets.UTF_8));
        os.close();

        int code = conn.getResponseCode();
        BufferedReader br = new BufferedReader(new InputStreamReader(
            code >= 400 ? conn.getErrorStream() : conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        conn.disconnect();

        return new HttpsResult(code, sb.toString(), null);
    }

    private static class HttpsResult {
        int code;
        String body;
        String setCookie;
        HttpsResult(int code, String body, String setCookie) {
            this.code = code;
            this.body = body;
            this.setCookie = setCookie;
        }
    }

    class NescoBridge {
        @JavascriptInterface
        public void saveDescoCookies() {
            CookieManager cm = CookieManager.getInstance();
            String cookies = cm.getCookie(DESCO_PORTAL);
            if (cookies != null) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString("descoCookies", cookies)
                    .apply();
            }
        }

        @JavascriptInterface
        public String getDescoCookies() {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String activeId = prefs.getString("descoActiveId", "");
            if (!activeId.isEmpty()) {
                try {
                    JSONArray arr = loadDescoAccounts(prefs);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        if (activeId.equals(o.optString("id"))) return o.optString("cookies", "");
                    }
                } catch (Exception e) { /* fall through to legacy key */ }
            }
            return prefs.getString("descoCookies", "");
        }

        /** Saved DESCO accounts as JSON: [{id,label,active,addedAt}] — cookie
            values are deliberately NOT exposed to JavaScript. */
        @JavascriptInterface
        public String getDescoAccounts() {
            try {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                JSONArray arr = loadDescoAccounts(prefs);
                String activeId = prefs.getString("descoActiveId", "");
                JSONArray out = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    JSONObject j = new JSONObject();
                    j.put("id", o.optString("id"));
                    j.put("label", o.optString("label"));
                    j.put("addedAt", o.optLong("addedAt"));
                    j.put("active", activeId.equals(o.optString("id")));
                    out.put(j);
                }
                return out.toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        /** Opens the official portal so the user can sign in to another account;
            onPageFinished captures the session and stores it as a new account. */
        @JavascriptInterface
        public void openDescoLogin() {
            runOnUiThread(() -> {
                descoFlowGen++;          // invalidate probes from any earlier attempt
                descoValidating = false;
                addingDescoAccount = true;
                clearPortalCookies(); // fresh login form, not an existing session
                webView.loadUrl(DESCO_PORTAL + "/");
            });
        }

        @JavascriptInterface
        public void removeDescoAccount(String id) {
            try {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                JSONArray arr = loadDescoAccounts(prefs);
                JSONArray out = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    if (!id.equals(o.optString("id"))) out.put(o);
                }
                String activeId = prefs.getString("descoActiveId", "");
                SharedPreferences.Editor ed = prefs.edit().putString("descoAccounts", out.toString());
                if (id.equals(activeId)) {
                    if (out.length() > 0) {
                        JSONObject next = out.getJSONObject(out.length() - 1);
                        ed.putString("descoActiveId", next.optString("id"));
                        applyCookiesToJar(next.optString("cookies"));
                    } else {
                        ed.remove("descoActiveId");
                        clearPortalCookies();
                    }
                }
                ed.apply();
            } catch (Exception e) {
                Log.w(TAG, "removeDescoAccount failed", e);
            }
        }

        @JavascriptInterface
        public void closeDescoLogin() {
            runOnUiThread(() -> {
                addingDescoAccount = false;
                webView.loadUrl(WEB_URL);
            });
        }

        @JavascriptInterface
        public void setDescoActive(String id) {
            try {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                JSONArray arr = loadDescoAccounts(prefs);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    if (id.equals(o.optString("id"))) {
                        prefs.edit().putString("descoActiveId", id).apply();
                        applyCookiesToJar(o.optString("cookies"));
                        return;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "setDescoActive failed", e);
            }
        }

        /** CORS-free DESCO API GET: runs on the bridge thread, replies through
            window.__descoApiResult(cbId, {ok,status,body}). */
        @JavascriptInterface
        public void descoApiGetAsync(final String url, final String cbId) {
            new Thread(() -> {
                String payload;
                try {
                    HttpsResult r = descoHttpGet(url, getDescoCookies());
                    payload = descoPayload(r.code, r.body);
                } catch (Exception e) {
                    payload = "{\"ok\":false,\"status\":0,\"body\":\"\"}";
                }
                replyDescoApi(cbId, payload);
            }).start();
        }

        /** CORS-free DESCO API POST (JSON body): used by the portal-v2
            /accounts/link endpoint, which requires an X-XSRF-TOKEN header. */
        @JavascriptInterface
        public void descoApiPostAsync(final String url, final String body, final String cbId) {
            new Thread(() -> {
                String payload;
                try {
                    HttpsResult r = descoHttpPost(url, body, getDescoCookies());
                    payload = descoPayload(r.code, r.body);
                } catch (Exception e) {
                    payload = "{\"ok\":false,\"status\":0,\"body\":\"\"}";
                }
                replyDescoApi(cbId, payload);
            }).start();
        }

        @JavascriptInterface
        public String nescoLookupSync(String cust) {
            return nescoLookupImpl(cust);
        }

        @JavascriptInterface
        public void nescoLookupAsync(final String cust, final String cbId) {
            new Thread(() -> {
                String result;
                try {
                    result = nescoLookupImpl(cust);
                } catch (Exception e) {
                    result = "{\"ok\":false,\"error\":\"lookup failed\"}";
                }
                final String escaped = result
                    .replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\n", "\\n")
                    .replace("\r", "");
                mainHandler.post(() -> {
                    webView.evaluateJavascript(
                        "(function(){if(typeof window.__nescoResult==='function'){window.__nescoResult('"
                            + cbId + "','" + escaped + "');}})()",
                        null);
                });
            }).start();
        }

        @JavascriptInterface
        public String clipboardRead() {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            android.content.ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence text = clip.getItemAt(0).getText();
                return text != null ? text.toString() : "";
            }
            return "";
        }


        @JavascriptInterface
        public void clipboardWrite(String text) {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("Meter Manager", text);
            cm.setPrimaryClip(clip);
        }

        @JavascriptInterface
        public void googleSignIn() {
            mainHandler.post(() -> {
                googleSignInClient.signOut().addOnCompleteListener(task -> {
                    Intent signInIntent = googleSignInClient.getSignInIntent();
                    startActivityForResult(signInIntent, RC_SIGN_IN);
                });
            });
        }

        @JavascriptInterface
        public String getPendingGoogleToken() {
            return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_PENDING_GOOGLE_TOKEN, null);
        }

        @JavascriptInterface
        public String getPendingGoogleEmail() {
            return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_PENDING_GOOGLE_EMAIL, "");
        }

        @JavascriptInterface
        public void clearPendingGoogleToken() {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .remove(KEY_PENDING_GOOGLE_TOKEN)
                .remove(KEY_PENDING_GOOGLE_EMAIL)
                .apply();
        }

        @JavascriptInterface
        public void shareText(String title, String text) {
            mainHandler.post(() -> {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_SUBJECT, title);
                intent.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(intent, title));
            });
        }

        @JavascriptInterface
        public void saveFileWithPicker(String content, String defaultName) {
            pendingSaveContent = content;
            mainHandler.post(() -> {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TITLE, defaultName);
                startActivityForResult(intent, 1001);
            });
        }

        @JavascriptInterface
        public void downloadAndInstallApk(String urlStr) {
            downloadApkInternal(urlStr, "MeterManager-update.apk");
        }

        @JavascriptInterface
        public int getDownloadProgress() {
            if (pendingDownloadId == -1) return 0;
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm == null) return 0;
            DownloadManager.Query query = new DownloadManager.Query();
            query.setFilterById(pendingDownloadId);
            Cursor cursor = dm.query(query);
            if (cursor != null && cursor.moveToFirst()) {
                int status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    installApk(pendingDownloadId);
                    cursor.close();
                    return 100;
                }
                int bytesDownloaded = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                int bytesTotal = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                cursor.close();
                if (bytesTotal > 0) {
                    return (int) ((bytesDownloaded * 100L) / bytesTotal);
                }
            }
            return 0;
        }

        /* Asks the native Drive scanner whether a newer APK of this app is present in
           the release folder. The JSON result is delivered to
           window.__driveUpdateResult(cbId, json). */
        @JavascriptInterface
        public void scanDriveForUpdate(final String cbId) {
            new Thread(() -> {
                String result;
                try {
                    result = scanDriveForUpdateJson();
                } catch (Exception e) {
                    Log.e(TAG, "Drive update scan failed", e);
                    result = "{\"ok\":false,\"error\":\"" + escapeJsString(String.valueOf(e.getMessage())) + "\"}";
                }
                final String payload = escapeJsString(result);
                mainHandler.post(() -> webView.evaluateJavascript(
                    "(function(){if(typeof window.__driveUpdateResult==='function'){window.__driveUpdateResult('"
                        + cbId + "','" + payload + "');}})()",
                    null));
            }).start();
        }

        /* Downloads the APK that the Drive scan found and starts the installer. */
        @JavascriptInterface
        public void downloadDriveUpdate(String fileId, String fileName) {
            if (fileId == null || fileId.trim().isEmpty()) {
                webView.evaluateJavascript("if(window.toast) window.toast('Update file not found', true);", null);
                return;
            }
            downloadApkInternal(driveDownloadUrl(fileId), fileName);
        }

        @JavascriptInterface
        public void loadFileWithPicker() {
            mainHandler.post(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("text/plain");
                startActivityForResult(intent, 1002);
            });
        }

        @JavascriptInterface
        public String getBase64FromFile(String path) {
            try {
                java.io.InputStream is = getAssets().open(path);
                byte[] bytes = new byte[is.available()];
                is.read(bytes);
                is.close();
                return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void setStatusBarColor(String colorHex) {
            mainHandler.post(() -> setStatusBarColorDirect(colorHex));
        }

        @JavascriptInterface
        public void saveTheme(String theme) {
            getSharedPreferences("MeterManager", MODE_PRIVATE).edit().putString("theme", theme).apply();
        }

        @JavascriptInterface
        public boolean isSystemDarkMode() {
            int nightMask = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return nightMask == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        }
    }

    /* Multi-account "Add account" flow: while true the WebView sits on the
       DESCO portal and we watch for a fresh DESCO_SESSION to appear. */
    private volatile boolean addingDescoAccount = false;
    /* Serializes the async "is this session really signed in?" API probe. */
    private volatile boolean descoValidating = false;
    /* Bumped on every openDescoLogin so a stale probe from a cancelled flow
       can never save its cookies or navigate the WebView. */
    private volatile int descoFlowGen = 0;
    /* JSON handed to JS (__descoAccountAdded) once the app page is back. */
    private volatile String pendingDescoNotify = null;

    /* ---------- multi-account DESCO login ---------- */

    /** "DESCO_SESSION=...; XSRF-TOKEN=..." out of a raw Cookie header, or "". */
    private static String extractDescoSessionCookies(String all) {
        StringBuilder sb = new StringBuilder();
        for (String pair : all.split(";")) {
            String p = pair.trim();
            int eq = p.indexOf('=');
            if (eq <= 0) continue;
            String name = p.substring(0, eq);
            if ("DESCO_SESSION".equals(name) || "XSRF-TOKEN".equals(name)) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(p);
            }
        }
        return sb.toString();
    }

    /** Expires every cookie the portal set, so "Add account" shows a fresh login form. */
    private void clearPortalCookies() {
        try {
            CookieManager cm = CookieManager.getInstance();
            String all = cm.getCookie(DESCO_PORTAL);
            if (all != null) {
                for (String p : all.split(";")) {
                    String name = p.split("=")[0].trim();
                    if (!name.isEmpty()) cm.setCookie(DESCO_PORTAL, name + "=; Max-Age=0; path=/");
                }
            }
            cm.flush();
        } catch (Exception e) {
            Log.w(TAG, "clear portal cookies failed", e);
        }
    }

    /** Writes a stored cookie string back into the WebView cookie jar. */
    private void applyCookiesToJar(String cookieHeader) {
        try {
            CookieManager cm = CookieManager.getInstance();
            if (cookieHeader != null && !cookieHeader.isEmpty()) {
                for (String p : cookieHeader.split(";")) {
                    String pair = p.trim();
                    if (!pair.isEmpty()) cm.setCookie(DESCO_PORTAL, pair + "; path=/");
                }
            }
            cm.flush();
        } catch (Exception e) {
            Log.w(TAG, "apply cookies failed", e);
        }
    }

    /** Puts the active account's saved session into the jar (called on app load). */
    private void restoreActiveDescoCookies() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String activeId = prefs.getString("descoActiveId", "");
            if (activeId.isEmpty()) return;
            JSONArray arr = loadDescoAccounts(prefs);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (activeId.equals(o.optString("id"))) {
                    applyCookiesToJar(o.optString("cookies"));
                    return;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "restore desco cookies failed", e);
        }
    }

    /** Stored DESCO accounts with junk sessions filtered out.  An entry that
        never obtained a DESCO_SESSION (an XSRF-TOKEN alone is set on every
        anonymous portal visit) cannot authenticate, so it is dropped from the
        list — and if the active id pointed at one, it is re-pointed at the
        newest surviving account.  Prefs are rewritten only when something
        actually changed. */
    private JSONArray loadDescoAccounts(SharedPreferences prefs) {
        try {
            JSONArray arr = new JSONArray(prefs.getString("descoAccounts", "[]"));
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (o.optString("cookies").contains("DESCO_SESSION=")) out.put(o);
            }
            if (out.length() != arr.length()) {
                String activeId = prefs.getString("descoActiveId", "");
                boolean activeGone = !activeId.isEmpty();
                for (int i = 0; i < out.length() && activeGone; i++) {
                    if (activeId.equals(out.getJSONObject(i).optString("id"))) activeGone = false;
                }
                SharedPreferences.Editor ed = prefs.edit().putString("descoAccounts", out.toString());
                if (activeGone) {
                    if (out.length() > 0) {
                        ed.putString("descoActiveId", out.getJSONObject(out.length() - 1).optString("id"));
                    } else {
                        ed.remove("descoActiveId");
                    }
                }
                ed.apply();
                Log.i(TAG, "pruned " + (arr.length() - out.length()) + " invalid DESCO account(s)");
            }
            return out;
        } catch (Exception e) {
            Log.w(TAG, "loadDescoAccounts failed", e);
            return new JSONArray();
        }
    }

    /** Called on every DESCO portal page finish while "Add account" is running.
        Any portal visit carries an XSRF-TOKEN, so only a DESCO_SESSION counts
        as a login — and even then the session is verified against the DESCO
        API before it is stored, so a rejected or half-finished login can never
        become an account. */
    private void maybeFinishDescoAdd(final WebView view) {
        if (descoValidating) return; // a probe is already running
        String all = CookieManager.getInstance().getCookie(DESCO_PORTAL);
        String session = all == null ? "" : extractDescoSessionCookies(all);
        if (session.isEmpty() || !session.contains("DESCO_SESSION=")) return; // still on the login form
        descoValidating = true;
        final String cookies = session;
        final int gen = descoFlowGen;
        new Thread(() -> {
            int code = 0;
            try {
                code = descoHttpGet(DESCO_PORTAL + "/api/accounts", cookies).code;
            } catch (Exception e) {
                Log.w(TAG, "desco add probe failed", e);
            }
            // Auth is checked before routing: 401 = not signed in, 200 = the
            // session sees its linked accounts (any other 4xx just means the
            // probe request was malformed — still treated as signed in).
            boolean authed = (code >= 200 && code < 300) || (code >= 400 && code < 500 && code != 401);
            Log.i(TAG, "desco add probe: HTTP " + code + " authed=" + authed);
            if (!authed || gen != descoFlowGen || !addingDescoAccount) {
                // Not signed in yet (or the flow was cancelled) — keep waiting.
                mainHandler.post(() -> {
                descoValidating = false;
                webView.evaluateJavascript("var b=document.getElementById('mm-back-btn');if(b)b.remove();", null);
            });
                return;
            }
            // Signed in. Best-effort label: the dashboard shows the 8-digit
            // account number; retry briefly because the SPA renders late.
            mainHandler.post(() -> extractDescoLabel(view, cookies, gen, 0));
        }).start();
    }

    /** Reads the 8-digit account number off the portal page for the account
        label, retrying a few times while the dashboard SPA renders. */
    private void extractDescoLabel(final WebView view, final String cookies, final int gen, final int attempt) {
        view.evaluateJavascript(
            "(function(){var t=(document&&document.body&&document.body.innerText)||'';" +
            "var m=t.match(/\\b\\d{8}\\b/);return m?m[0]:''})()",
            value -> {
                String label = (value == null || "null".equals(value)) ? "" : value.replace("\"", "");
                if (label.isEmpty() && attempt < 3 && addingDescoAccount && gen == descoFlowGen) {
                    view.postDelayed(() -> extractDescoLabel(view, cookies, gen, attempt + 1), 600);
                    return;
                }
                descoValidating = false;
                if (!addingDescoAccount || gen != descoFlowGen) return; // user backed out
                finishDescoAdd(cookies, label);
            });
    }

    /** Saves the captured session as an account, activates it, returns to the app. */
    private void finishDescoAdd(String cookies, String label) {
        String displayLabel = label;
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            JSONArray arr = loadDescoAccounts(prefs);
            String id = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (cookies.equals(o.optString("cookies"))) { id = o.optString("id"); break; }
            }
            if (id == null) {
                id = "d" + System.currentTimeMillis();
                if (displayLabel == null || displayLabel.isEmpty()) displayLabel = "Account " + (arr.length() + 1);
                JSONObject o = new JSONObject();
                o.put("id", id);
                o.put("label", displayLabel);
                o.put("addedAt", System.currentTimeMillis());
                o.put("cookies", cookies);
                arr.put(o);
            } else if (displayLabel == null || displayLabel.isEmpty()) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    if (id.equals(o.optString("id"))) { displayLabel = o.optString("label"); break; }
                }
            }
            prefs.edit().putString("descoAccounts", arr.toString()).putString("descoActiveId", id).apply();
            JSONObject notify = new JSONObject();
            notify.put("label", displayLabel == null ? "" : displayLabel);
            notify.put("count", arr.length());
            pendingDescoNotify = notify.toString();
        } catch (Exception e) {
            Log.w(TAG, "save desco account failed", e);
        }
        webView.loadUrl(WEB_URL);
    }

    /** GET for the DESCO API: no Origin header (CORS is browser-only) and the
        active account's session cookie attached. */
    private HttpsResult descoHttpGet(String urlStr, String cookie) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36");
        conn.setRequestProperty("Accept", "application/json");
        if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
        conn.setInstanceFollowRedirects(false);

        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            BufferedReader br = new BufferedReader(new InputStreamReader(in));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
        }
        conn.disconnect();
        return new HttpsResult(code, sb.toString(), null);
    }

    /** POST for the DESCO API: JSON body plus the X-XSRF-TOKEN header the
        portal's CSRF protection requires on every non-GET request. */
    private HttpsResult descoHttpPost(String urlStr, String jsonBody, String cookie) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Content-Type", "application/json");
        if (cookie != null && !cookie.isEmpty()) {
            conn.setRequestProperty("Cookie", cookie);
            String xsrf = xsrfTokenFromCookies(cookie);
            if (xsrf != null && !xsrf.isEmpty()) conn.setRequestProperty("X-XSRF-TOKEN", xsrf);
        }
        conn.setInstanceFollowRedirects(false);
        conn.setDoOutput(true);
        byte[] data = (jsonBody == null ? "{}" : jsonBody).getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) { os.write(data); }

        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            BufferedReader br = new BufferedReader(new InputStreamReader(in));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
        }
        conn.disconnect();
        return new HttpsResult(code, sb.toString(), null);
    }

    /** Pulls the XSRF-TOKEN value out of a "Cookie:" header string. The portal
        sends it URL-encoded; the X-XSRF-TOKEN header needs the decoded form. */
    private static String xsrfTokenFromCookies(String cookieHeader) {
        if (cookieHeader == null) return null;
        for (String part : cookieHeader.split(";")) {
            String p = part.trim();
            if (p.startsWith("XSRF-TOKEN=")) {
                String v = p.substring("XSRF-TOKEN=".length());
                try { return URLDecoder.decode(v, "UTF-8"); }
                catch (Exception e) { return v; }
            }
        }
        return null;
    }

    /** JSON envelope shared by descoApiGetAsync / descoApiPostAsync. */
    private static String descoPayload(int code, String body) throws Exception {
        JSONObject out = new JSONObject();
        out.put("ok", code >= 200 && code < 300);
        out.put("status", code);
        out.put("body", body == null ? "" : body);
        return out.toString();
    }

    /** Escapes the payload and hands it to window.__descoApiResult(cbId, …). */
    private void replyDescoApi(final String cbId, final String payload) {
        final String escaped = payload
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "");
        mainHandler.post(() -> webView.evaluateJavascript(
            "(function(){if(typeof window.__descoApiResult==='function'){window.__descoApiResult('"
                + cbId + "','" + escaped + "');}})()",
            null));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_SIGN_IN) {
            Task<com.google.android.gms.auth.api.signin.GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            try {
                com.google.android.gms.auth.api.signin.GoogleSignInAccount account = task.getResult(ApiException.class);
                String idToken = account.getIdToken();
                String email = account.getEmail() != null ? account.getEmail() : "";
                Log.d(TAG, "Google Sign-In success, idToken=" + (idToken != null ? "present" : "null"));
                if (idToken != null) {
                    // Store in SharedPreferences as fallback
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putString(KEY_PENDING_GOOGLE_TOKEN, idToken)
                        .putString(KEY_PENDING_GOOGLE_EMAIL, email)
                        .apply();

                    String escapedToken = idToken.replace("\\", "\\\\").replace("'", "\\'");
                    String escapedEmail = email.replace("\\", "\\\\").replace("'", "\\'");
                    String js = "(function(){if(typeof window.onGoogleSignInResult==='function'){window.onGoogleSignInResult('" + escapedToken + "','" + escapedEmail + "');return 'ok';}return 'nofunc';})()";
                    // Try immediately
                    webView.evaluateJavascript(js, value -> {
                        Log.d(TAG, "evaluateJavascript (immediate) result: " + value);
                    });
                    // Retry after delay in case WebView JS context wasn't ready
                    final String retryJs = js;
                    mainHandler.postDelayed(() -> {
                        webView.evaluateJavascript(retryJs, value -> {
                            Log.d(TAG, "evaluateJavascript (delayed) result: " + value);
                            // Clear pending token after successful delivery
                            if (value != null && value.contains("ok")) {
                                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                                    .remove(KEY_PENDING_GOOGLE_TOKEN)
                                    .remove(KEY_PENDING_GOOGLE_EMAIL)
                                    .apply();
                            }
                        });
                    }, 500);
                }
            } catch (ApiException e) {
                Log.e(TAG, "Google Sign-In failed: code=" + e.getStatusCode(), e);
                String msg = "Google sign-in failed (error " + e.getStatusCode() + ")";
                if (e.getStatusCode() == 10) msg = "Google Sign-In configuration error. Please update the app.";
                if (e.getStatusCode() == 12501) msg = "Google sign-in cancelled";
                if (e.getStatusCode() == 12500) msg = "Google sign-in failed. Please try again.";
                if (e.getStatusCode() == 12502) msg = "Google sign-in already in progress";
                if (e.getStatusCode() == 16) msg = "Google sign-in timeout. Check internet connection.";
                final String finalMsg = msg;
                webView.evaluateJavascript(
                    "if(window.toast) window.toast('" + finalMsg.replace("'", "\\'") + "', true);", null);
            }
            return;
        }
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null && pendingSaveContent != null) {
                try {
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        os.write(pendingSaveContent.getBytes("UTF-8"));
                        os.close();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "File write error", e);
                }
                pendingSaveContent = null;
            }
        } else if (requestCode == 1002 && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    BufferedReader br = new BufferedReader(new InputStreamReader(
                        getContentResolver().openInputStream(uri)));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    boolean first = true;
                    while ((line = br.readLine()) != null) {
                        if (!first) sb.append("\n");
                        sb.append(line);
                        first = false;
                    }
                    br.close();
                    String content = sb.toString();
                    String escaped = content.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "");
                    webView.evaluateJavascript(
                        "(function(){if(window._onFileLoaded)window._onFileLoaded('" + escaped + "');})()", null);
                } catch (Exception e) {
                    Log.e(TAG, "File read error", e);
                }
            }
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        pushThemeToWebView();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (updateReceiver != null) {
            try { unregisterReceiver(updateReceiver); } catch (Exception e) {}
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getData() != null) {
            Uri uri = intent.getData();
            webView.loadUrl(WEB_URL);
        }
    }

    /* ================= Google Drive update scanning ================= */

    private static String escapeJsString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }

    private String driveDownloadUrl(String fileId) {
        return "https://www.googleapis.com/drive/v3/files/" + fileId + "?alt=media&key=" + DRIVE_API_KEY;
    }

    private String driveHttpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("Accept", "application/json");
        try {
            int code = conn.getResponseCode();
            java.io.InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            if (is != null) {
                BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
            }
            if (code >= 400) throw new Exception("Drive HTTP " + code);
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private String driveListUrl(String query) throws Exception {
        return "https://www.googleapis.com/drive/v3/files?q=" + URLEncoder.encode(query, "UTF-8")
            + "&fields=" + URLEncoder.encode("files(id,name,mimeType,modifiedTime),nextPageToken", "UTF-8")
            + "&pageSize=1000&orderBy=" + URLEncoder.encode("modifiedTime desc", "UTF-8")
            + "&key=" + DRIVE_API_KEY;
    }

    /** Locates the "Android" subfolder of the release folder (falls back to the root). */
    private String driveReleaseFolderId() {
        try {
            String query = "'" + DRIVE_ROOT_FOLDER_ID + "' in parents and trashed=false"
                + " and mimeType='application/vnd.google-apps.folder' and name='" + DRIVE_ANDROID_SUBFOLDER + "'";
            org.json.JSONArray files = new org.json.JSONObject(driveHttpGet(driveListUrl(query))).optJSONArray("files");
            if (files != null && files.length() > 0) {
                String id = files.getJSONObject(0).optString("id", "");
                if (!id.isEmpty()) return id;
            }
        } catch (Exception e) {
            Log.w(TAG, "Drive release folder lookup failed", e);
        }
        return DRIVE_ROOT_FOLDER_ID;
    }

    private static int parseIntOr(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; }
    }

    /** Compares dotted version names numerically; returns >0 when a is newer. */
    private static int compareVersions(String a, String b) {
        String[] ap = (a == null ? "" : a).split("\\.");
        String[] bp = (b == null ? "" : b).split("\\.");
        int len = Math.max(ap.length, bp.length);
        for (int i = 0; i < len; i++) {
            int av = i < ap.length ? parseIntOr(ap[i], 0) : 0;
            int bv = i < bp.length ? parseIntOr(bp[i], 0) : 0;
            if (av != bv) return av > bv ? 1 : -1;
        }
        return 0;
    }

    /**
     * Lists the release folder, picks the newest Meter Manager APK (by build number,
     * then version name) and compares it with the installed package.
     */
    private String scanDriveForUpdateJson() throws Exception {
        String pkg = getPackageName();
        int installedCode = 0;
        String installedName = "";
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(pkg, 0);
            installedCode = (int) (Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode);
            installedName = pi.versionName == null ? "" : pi.versionName;
        } catch (Exception e) {
            Log.w(TAG, "Cannot read installed version", e);
        }

        String folderId = driveReleaseFolderId();
        String query = "'" + folderId + "' in parents and trashed=false and name contains 'Meter Manager'";

        String bestId = null, bestFileName = "", bestVersion = "";
        int bestCode = -1;
        String pageToken = null;
        do {
            String url = driveListUrl(query) + (pageToken == null
                ? "" : "&pageToken=" + URLEncoder.encode(pageToken, "UTF-8"));
            org.json.JSONObject page = new org.json.JSONObject(driveHttpGet(url));
            org.json.JSONArray files = page.optJSONArray("files");
            if (files != null) {
                for (int i = 0; i < files.length(); i++) {
                    org.json.JSONObject f = files.getJSONObject(i);
                    String name = f.optString("name", "");
                    String lower = name.toLowerCase();
                    if (!lower.endsWith(".apk")) continue;
                    if (!lower.contains(pkg) && !lower.startsWith("meter manager")) continue;

                    java.util.regex.Matcher vm =
                        java.util.regex.Pattern.compile("_v([0-9][0-9.]*)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(name);
                    String versionName = vm.find() ? vm.group(1) : "";
                    java.util.regex.Matcher bm =
                        java.util.regex.Pattern.compile("_(?:build|buid)_(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(name);
                    int code = bm.find() ? parseIntOr(bm.group(1), -1) : -1;

                    boolean better;
                    if (bestId == null) better = true;
                    else if (code != bestCode) better = code > bestCode;
                    else better = compareVersions(versionName, bestVersion) > 0;

                    if (better) {
                        bestId = f.optString("id", "");
                        bestFileName = name;
                        bestVersion = versionName;
                        bestCode = code;
                    }
                }
            }
            String next = page.optString("nextPageToken", "");
            pageToken = next.isEmpty() ? null : next;
        } while (pageToken != null);

        if (bestId == null || bestId.isEmpty()) {
            return "{\"ok\":true,\"available\":false,\"installedCode\":" + installedCode
                + ",\"installedName\":\"" + escapeJsString(installedName)
                + "\",\"reason\":\"no_apk_in_drive\"}";
        }

        boolean newer = bestCode > 0
            ? bestCode > installedCode
            : compareVersions(bestVersion, installedName) > 0;

        return "{\"ok\":true,\"available\":" + newer
            + ",\"installedCode\":" + installedCode
            + ",\"installedName\":\"" + escapeJsString(installedName)
            + "\",\"latestName\":\"" + escapeJsString(bestVersion)
            + "\",\"latestCode\":" + bestCode
            + ",\"fileName\":\"" + escapeJsString(bestFileName)
            + "\",\"fileId\":\"" + escapeJsString(bestId)
            + "\",\"downloadUrl\":\"" + escapeJsString(driveDownloadUrl(bestId))
            + "\"}";
    }

    /** Downloads an APK through DownloadManager; the install receiver picks it up. */
    private void downloadApkInternal(final String urlStr, final String fileName) {
        mainHandler.post(() -> {
            try {
                String safe = (fileName == null || fileName.trim().isEmpty())
                    ? "MeterManager-update.apk"
                    : fileName.trim().replaceAll("[^A-Za-z0-9._-]", "_");
                if (!safe.toLowerCase().endsWith(".apk")) safe += ".apk";

                java.io.File dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
                if (dir != null) {
                    java.io.File target = new java.io.File(dir, safe);
                    if (target.exists() && !target.delete()) {
                        Log.w(TAG, "Could not delete stale update file " + target);
                    }
                    pendingApkPath = target.getAbsolutePath();
                    getSharedPreferences("MeterManager", MODE_PRIVATE).edit().putString("pendingApkPath", pendingApkPath).apply();
                }

                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(urlStr));
                request.setTitle("Meter Manager Update");
                request.setDescription("Downloading latest version...");
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalFilesDir(MainActivity.this,
                    android.os.Environment.DIRECTORY_DOWNLOADS, safe);
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (dm == null) return;
                pendingDownloadId = dm.enqueue(request);
                getSharedPreferences("MeterManager", MODE_PRIVATE).edit().putLong("pendingDownloadId", pendingDownloadId).apply();
                webView.evaluateJavascript("if(window.toast) window.toast('Downloading update...', false);", null);
            } catch (Exception e) {
                Log.e(TAG, "Download update error", e);
                webView.evaluateJavascript("if(window.toast) window.toast('Update download failed', true);", null);
            }
        });
    }

    /** Deletes APKs left in the app cache by earlier update attempts. */
    private void sweepDownloadedApks() {
        try {
            java.io.File dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
            if (dir == null || !dir.isDirectory()) return;
            java.io.File[] files = dir.listFiles();
            if (files == null) return;
            for (java.io.File f : files) {
                if (f.isFile() && f.getName().toLowerCase().endsWith(".apk")) f.delete();
            }
        } catch (Exception e) {
            Log.w(TAG, "APK cache sweep failed", e);
        }
    }

    private void installApk(long downloadId) {
        try {
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm == null) return;
            Uri uri = dm.getUriForDownloadedFile(downloadId);
            if (uri == null) {
                // Fallback: try querying downloaded file from query
                DownloadManager.Query query = new DownloadManager.Query();
                query.setFilterById(downloadId);
                Cursor cursor = dm.query(query);
                if (cursor != null && cursor.moveToFirst()) {
                    int columnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
                    if (columnIndex >= 0) {
                        String uriString = cursor.getString(columnIndex);
                        if (uriString != null) uri = Uri.parse(uriString);
                    }
                    cursor.close();
                }
            }
            if (uri == null && pendingApkPath != null) {
                java.io.File f = new java.io.File(pendingApkPath);
                if (f.exists()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
                    } else {
                        uri = Uri.fromFile(f);
                    }
                }
            }
            if (uri == null) {
                webView.evaluateJavascript("if(window.toast) window.toast('Download failed. File not found.', true);", null);
                return;
            }

            // If uri is file://, convert to FileProvider content:// URI on Android N+
            if ("file".equals(uri.getScheme()) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                java.io.File f = new java.io.File(uri.getPath());
                if (f.exists()) {
                    uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
                }
            }

            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(install);

            /* Once the system installer has had time to read the APK, drop both the
               DownloadManager entry and the cached file so nothing is left behind. */
            final String apkPath = pendingApkPath;
            pendingApkPath = null;
            mainHandler.postDelayed(() -> {
                try { dm.remove(downloadId); } catch (Exception ignored) {}
                try {
                    if (apkPath != null) {
                        java.io.File f = new java.io.File(apkPath);
                        if (f.exists()) f.delete();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to delete cached APK", e);
                }
            }, 60000);
        } catch (Exception e) {
            Log.e(TAG, "install apk error", e);
            webView.evaluateJavascript("if(window.toast) window.toast('Install failed: " +
                (e.getMessage() != null ? e.getMessage().replace("'", "\\\\'") : "unknown") + "', true);", null);
        }
    }


    private void pushThemeToWebView() {
        mainHandler.postDelayed(() -> {
            int nightMask = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            boolean isDark = nightMask == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            webView.evaluateJavascript(
                "window._androidDarkMode=" + isDark + ";if(typeof applyTheme==='function')applyTheme()", null);
            String color = isDark ? "#000000" : "#f2f4f8";
            setStatusBarColorDirect(color);
        }, 150);
    }

    private void checkPendingDownload() {
        if (pendingDownloadId == -1) return;
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (dm == null) return;
        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(pendingDownloadId);
        Cursor cursor = dm.query(query);
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                int status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    installApk(pendingDownloadId);
                    pendingDownloadId = -1;
                    getSharedPreferences("MeterManager", MODE_PRIVATE).edit().remove("pendingDownloadId").apply();
                }
            }
            cursor.close();
        }
    }
}
