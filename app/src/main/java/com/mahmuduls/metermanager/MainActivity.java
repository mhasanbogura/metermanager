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
import android.webkit.JavascriptInterface;
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
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private WebView webView;
    private static final String TAG = "MeterManager";
    private static final String WEB_URL = "https://mhasanbogura.github.io/prepaid-meter-manager/";
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String savedTheme = getSharedPreferences("MeterManager", MODE_PRIVATE).getString("theme", "light");
        String resolved = savedTheme;
        if ("system".equals(savedTheme)) {
            int nightMask = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            resolved = (nightMask == android.content.res.Configuration.UI_MODE_NIGHT_YES) ? "oled" : "light";
        }
        setStatusBarColorDirect("light".equals(resolved) ? "#e8ebf0" : "oled".equals(resolved) ? "#0a0a0a" : "#1a1f2a");

        getWindow().setDecorFitsSystemWindows(true);

        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webView.setBackgroundColor(0xFF000000);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setUserAgentString("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

        webView.addJavascriptInterface(new NescoBridge(), "NescoBridge");

        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id))
                .requestEmail()
                .build();
        googleSignInClient = GoogleSignIn.getClient(this, gso);

        webView.setWebChromeClient(new WebChromeClient() {
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
                injectOverrides();
                view.evaluateJavascript(
                    "(function(){var t=document.querySelector('meta[name=theme-color]');return t?t.content:'light'})()",
                    value -> {
                        String color = value != null ? value.replace("\"", "") : "light";
                        if ("light".equals(color)) color = "#e8ebf0";
                        else if ("dark".equals(color)) color = "#1a1f2a";
                        else if ("oled".equals(color)) color = "#0a0a0a";
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
        webView.evaluateJavascript(
            "(function(){" +
            "  var d=document.getElementById('dlg');" +
            "  if(d && !d.hidden){" +
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
    protected void onResume() {
        super.onResume();
        pushThemeToWebView();
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

    private void pushThemeToWebView() {
        mainHandler.postDelayed(() -> {
            int nightMask = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            boolean isDark = nightMask == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            webView.evaluateJavascript(
                "window._androidDarkMode=" + isDark + ";if(typeof applyTheme==='function')applyTheme()", null);
            String color = isDark ? "#0a0a0a" : "#e8ebf0";
            setStatusBarColorDirect(color);
        }, 150);
    }
}
