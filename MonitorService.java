package com.flashsignal.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MonitorService extends Service {
    public static final String ACTION_SET_ALL = "com.flashsignal.mobile.SET_ALL";
    public static final String ACTION_SET_ENABLED = "com.flashsignal.mobile.SET_ENABLED";
    public static final String ACTION_SCAN_NOW = "com.flashsignal.mobile.SCAN_NOW";
    public static final String EXTRA_VALUE = "value";

    private static final String SERVICE_CHANNEL = "monitor_service";
    private static final String SIGNAL_CHANNEL = "match_signals";
    private static final int SERVICE_NOTIFICATION_ID = 7001;
    private static final long STATS_RESCAN_MS = 15_000L;
    private static final long WORKER_TIMEOUT_MS = 25_000L;
    private static final long MONITOR_RELOAD_MS = 180_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Models.MatchInfo> matches = new LinkedHashMap<>();
    private final Map<String, Models.StatInfo> stats = new LinkedHashMap<>();
    private final List<Models.Signal> signals = new ArrayList<>();
    private final Map<String, List<Models.ShotPoint>> shotHistory = new HashMap<>();
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private final Set<String> queued = new HashSet<>();
    private final Set<String> seenSignalKeys = new HashSet<>();

    private WebView monitorWebView;
    private WebView workerWebView;
    private PowerManager.WakeLock wakeLock;
    private String monitorScript = "";
    private String detailScript = "";
    private String currentWorkerId = null;
    private long currentWorkerToken = 0L;
    private long lastSnapshotAt = 0L;
    private long lastMonitorReloadAt = 0L;
    private int notificationSeq = 8000;
    private String lastMonitorError = "";
    private final ExecutorService stateWriter = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate() {
        super.onCreate();

        // MonitorService runs in :monitor. Android 9+ requires a separate
        // WebView data directory when another process in the same app also
        // uses WebView (the dashboard in MainActivity).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WebView.setDataDirectorySuffix("monitor");
        }

        createChannels();
        startForeground(SERVICE_NOTIFICATION_ID, buildServiceNotification("Monitor hazırlanıyor…"));
        restoreSeenKeys();
        acquireWakeLock();
        monitorScript = readAsset("monitor.js");
        detailScript = readAsset("detail.js");
        main.post(this::createWebViews);
        main.postDelayed(tick, 2500L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_SET_ALL: {
                    boolean value = intent.getBooleanExtra(EXTRA_VALUE, false);
                    StateStore.setAllMatchesMode(this, value);
                    if (!value) {
                        Iterator<Models.Signal> it = signals.iterator();
                        while (it.hasNext()) {
                            if (it.next().rule.startsWith("ALL_")) it.remove();
                        }
                    }
                    rebuildQueue(true);
                    evaluateSnapshotRules();
                    publishState();
                    break;
                }
                case ACTION_SET_ENABLED: {
                    boolean value = intent.getBooleanExtra(EXTRA_VALUE, true);
                    StateStore.setEnabled(this, value);
                    if (!value) {
                        queue.clear();
                        queued.clear();
                    } else {
                        forceMonitorSnapshot();
                        rebuildQueue(true);
                    }
                    publishState();
                    break;
                }
                case ACTION_SCAN_NOW:
                    forceMonitorSnapshot();
                    rebuildQueue(true);
                    startNextWorker();
                    break;
                default:
                    break;
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (monitorWebView != null) monitorWebView.destroy();
        if (workerWebView != null) workerWebView.destroy();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stateWriter.shutdownNow();
        super.onDestroy();
    }

    private void createChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel service = new NotificationChannel(
                SERVICE_CHANNEL,
                "Canlı takip servisi",
                NotificationManager.IMPORTANCE_LOW
        );
        service.setDescription("Flashscore canlı maç takibini açık tutar.");
        nm.createNotificationChannel(service);

        NotificationChannel signal = new NotificationChannel(
                SIGNAL_CHANNEL,
                "Maç sinyalleri",
                NotificationManager.IMPORTANCE_HIGH
        );
        signal.setDescription("Kurala uyan canlı maç bildirimleri.");
        nm.createNotificationChannel(signal);
    }

    private Notification buildServiceNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return new Notification.Builder(this, SERVICE_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("Flashscore Canlı Sinyaller")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void updateServiceNotification() {
        String text;
        if (!StateStore.isEnabled(this)) {
            text = "Takip kapalı";
        } else if (lastSnapshotAt == 0L) {
            text = "Monitor hazırlanıyor…";
        } else {
            long sec = Math.max(0, (System.currentTimeMillis() - lastSnapshotAt) / 1000L);
            text = matches.size() + " canlı maç · son veri " + sec + " sn önce";
        }
        getSystemService(NotificationManager.class)
                .notify(SERVICE_NOTIFICATION_ID, buildServiceNotification(text));
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FlashSignal::Monitor");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private WebView baseWebView() {
        WebView w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadsImagesAutomatically(false);
        s.setBlockNetworkImage(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setUserAgentString(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/140.0.0.0 Safari/537.36"
        );
        w.setVisibility(View.INVISIBLE);
        if (Build.VERSION.SDK_INT >= 26) {
            w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true);
        }
        int width = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY);
        int height = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY);
        w.measure(width, height);
        w.layout(0, 0, 1080, 1920);
        return w;
    }

    private void createWebViews() {
        CookieManager.getInstance().setAcceptCookie(true);

        monitorWebView = baseWebView();
        monitorWebView.addJavascriptInterface(new MonitorBridge(), "AndroidMonitor");
        monitorWebView.setWebChromeClient(new WebChromeClient());
        monitorWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                lastMonitorError = "";
                main.postDelayed(() -> injectMonitorScript(view), 1200L);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                lastMonitorError = description == null || description.isEmpty()
                        ? "Flashscore yüklenemedi"
                        : description;
                publishState();
            }
        });

        // Worker WebView is intentionally created lazily, only after the first
        // live match snapshot arrives. Creating dashboard + monitor + worker
        // WebViews together on cold start caused UI stalls / ANR on real phones.
        lastMonitorReloadAt = System.currentTimeMillis();
        monitorWebView.loadUrl("https://www.flashscore.com/football/");
    }

    private void ensureWorkerWebView() {
        if (workerWebView != null) return;

        workerWebView = baseWebView();
        workerWebView.addJavascriptInterface(new WorkerBridge(), "AndroidWorker");
        workerWebView.setWebChromeClient(new WebChromeClient());
        workerWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                final String id = currentWorkerId;
                final long token = currentWorkerToken;
                if (id == null) return;
                main.postDelayed(() -> {
                    if (id.equals(currentWorkerId) && token == currentWorkerToken) {
                        injectDetailScript(view, id);
                    }
                }, 900L);
            }
        });
    }

    private void injectMonitorScript(WebView view) {
        if (view == null || monitorScript.isEmpty()) return;
        view.evaluateJavascript(monitorScript, null);
    }

    private void injectDetailScript(WebView view, String matchId) {
        if (view == null || detailScript.isEmpty()) return;
        String prefix = "window.__mobileMatchId=" + JSONObject.quote(matchId) + ";";
        view.evaluateJavascript(prefix + detailScript, null);
    }

    private String readAsset(String file) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                getAssets().open(file), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (StateStore.isEnabled(MonitorService.this)) {
                forceMonitorSnapshot();
                rebuildQueue(false);
                startNextWorker();

                long now = System.currentTimeMillis();
                if (monitorWebView != null && now - lastMonitorReloadAt > MONITOR_RELOAD_MS) {
                    lastMonitorReloadAt = now;
                    monitorWebView.reload();
                }
            }
            updateServiceNotification();
            publishState();
            main.postDelayed(this, 5000L);
        }
    };

    private void forceMonitorSnapshot() {
        if (monitorWebView == null) return;
        monitorWebView.evaluateJavascript(
                "window.__flashMobileSnapshotNow && window.__flashMobileSnapshotNow();",
                null
        );
    }

    private final class MonitorBridge {
        @JavascriptInterface
        public void onSnapshot(String json) {
            main.post(() -> handleSnapshot(json));
        }

        @JavascriptInterface
        public void onDiagnostic(String text) {
            // Intentionally quiet in v0.1; state remains user-facing.
        }
    }

    private final class WorkerBridge {
        @JavascriptInterface
        public void onDetailResult(String matchId, String json) {
            main.post(() -> handleDetailResult(matchId, json));
        }
    }

    private void handleSnapshot(String json) {
        try {
            JSONArray arr = new JSONArray(json);
            long now = System.currentTimeMillis();
            Set<String> present = new HashSet<>();

            for (int i = 0; i < arr.length(); i++) {
                Models.MatchInfo m = Models.MatchInfo.fromJson(arr.getJSONObject(i));
                if (m.id.isEmpty()) continue;
                present.add(m.id);
                matches.put(m.id, m);
            }

            Iterator<Map.Entry<String, Models.MatchInfo>> it = matches.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Models.MatchInfo> e = it.next();
                if (!present.contains(e.getKey()) && now - e.getValue().updatedAt > 90_000L) {
                    it.remove();
                    stats.remove(e.getKey());
                    queued.remove(e.getKey());
                    queue.remove(e.getKey());
                }
            }

            lastSnapshotAt = now;
            evaluateSnapshotRules();
            rebuildQueue(false);
            publishState();
        } catch (Exception ignored) {
        }
    }

    private void rebuildQueue(boolean force) {
        if (!StateStore.isEnabled(this)) return;
        boolean all = StateStore.isAllMatchesMode(this);
        long now = System.currentTimeMillis();

        for (Models.MatchInfo m : matches.values()) {
            if (!m.live) continue;
            if (!all && !RuleEngine.isWatchedMatch(m)) continue;
            if (m.url == null || m.url.isEmpty()) continue;
            if (m.id.equals(currentWorkerId) || queued.contains(m.id)) continue;

            Models.StatInfo s = stats.get(m.id);
            boolean due = force || s == null || s.updatedAt == 0L || now - s.updatedAt >= STATS_RESCAN_MS;
            if (!due) continue;

            queue.add(m.id);
            queued.add(m.id);
            if (s == null) {
                s = new Models.StatInfo();
                s.matchId = m.id;
                stats.put(m.id, s);
            }
            s.status = "queued";
        }
    }

    private void startNextWorker() {
        if (currentWorkerId != null || !StateStore.isEnabled(this)) return;
        if (queue.isEmpty()) return;
        if (workerWebView == null) ensureWorkerWebView();
        if (workerWebView == null) return;

        while (!queue.isEmpty()) {
            String id = queue.poll();
            queued.remove(id);
            Models.MatchInfo m = matches.get(id);
            if (m == null || !m.live || m.url == null || m.url.isEmpty()) continue;
            if (!StateStore.isAllMatchesMode(this) && !RuleEngine.isWatchedMatch(m)) continue;

            currentWorkerId = id;
            long token = ++currentWorkerToken;
            Models.StatInfo s = stats.get(id);
            if (s == null) {
                s = new Models.StatInfo();
                s.matchId = id;
                stats.put(id, s);
            }
            s.status = "reading";
            s.attempts++;
            publishState();

            String url = m.url;
            if (!url.startsWith("http")) url = "https://www.flashscore.com" + url;
            if (!url.contains("#")) url += "#/match-summary/match-statistics/0";
            workerWebView.loadUrl(url);

            main.postDelayed(() -> {
                if (id.equals(currentWorkerId) && token == currentWorkerToken) {
                    Models.StatInfo timed = stats.get(id);
                    if (timed != null) {
                        timed.status = "timeout";
                        timed.updatedAt = System.currentTimeMillis();
                    }
                    currentWorkerId = null;
                    currentWorkerToken++;
                    publishState();
                    startNextWorker();
                }
            }, WORKER_TIMEOUT_MS);
            return;
        }
    }

    private void handleDetailResult(String matchId, String json) {
        if (matchId == null || !matchId.equals(currentWorkerId)) return;

        try {
            JSONObject o = new JSONObject(json);
            Models.StatInfo s = stats.get(matchId);
            if (s == null) {
                s = new Models.StatInfo();
                s.matchId = matchId;
                stats.put(matchId, s);
            }
            s.homeSot = nullableInt(o, "homeSot");
            s.awaySot = nullableInt(o, "awaySot");
            s.homeBig = nullableInt(o, "homeBig");
            s.awayBig = nullableInt(o, "awayBig");
            s.status = o.optString("status", "complete");
            s.updatedAt = System.currentTimeMillis();

            if (s.homeSot != null && s.awaySot != null) {
                recordShotPoint(matchId, s.homeSot + s.awaySot);
                evaluateDetailRules(matchId, s);
            }
        } catch (Exception ignored) {
        }

        currentWorkerId = null;
        currentWorkerToken++;
        cleanupSignals();
        publishState();
        startNextWorker();
    }

    private Integer nullableInt(JSONObject o, String key) {
        if (!o.has(key) || o.isNull(key)) return null;
        int v = o.optInt(key, Integer.MIN_VALUE);
        return v == Integer.MIN_VALUE ? null : v;
    }

    private void evaluateSnapshotRules() {
        boolean allMode = StateStore.isAllMatchesMode(this);

        for (Models.MatchInfo m : matches.values()) {
            if (!m.live) continue;

            if (allMode) {
                if (m.minute >= 15 && m.minute <= 30 && m.homeScore == 0 && m.awayScore == 0) {
                    addSignal("ALL_15_30", m, null, 0);
                }
                if (m.minute >= 60 && m.minute <= 82 && m.homeScore == 0 && m.awayScore == 0) {
                    addSignal("ALL_60_82", m, null, 0);
                }
                continue;
            }

            if (!RuleEngine.isWatchedMatch(m)) continue;

            if (m.minute >= 15 && m.minute <= 30) {
                if (m.homeScore == m.awayScore) addSignal("DRAW_15_30", m, null, 0);
                if (RuleEngine.watchedTeamLosing(m)) addSignal("LOSING_15_30", m, null, 0);
            }

            if (m.minute >= 60 && m.minute <= 82) {
                if (m.homeScore == m.awayScore) addSignal("DRAW_60_82", m, null, 0);
                if (RuleEngine.watchedTeamLosing(m)) addSignal("LOSING_60_82", m, null, 0);
            }

            if (RuleEngine.isRule1Minute(m.minute) && m.homeScore == 0 && m.awayScore == 0) {
                addSignal("CHECKPOINT", m, m.minute, 0);
            }
        }

        cleanupSignals();
    }

    private void evaluateDetailRules(String matchId, Models.StatInfo s) {
        if (StateStore.isAllMatchesMode(this)) return;
        Models.MatchInfo m = matches.get(matchId);
        if (m == null || !RuleEngine.isWatchedMatch(m)) return;

        if (m.minute >= 18 && m.minute <= 30
                && m.homeScore == 0 && m.awayScore == 0
                && RuleEngine.inRule2League(m.league)
                && s.homeSot != null && s.homeSot >= 2) {
            addSignal("RULE2", m, null, 0);
        }

        int delta = shotDelta5m(matchId);
        if (delta >= 4) {
            addSignal("SHOT_BURST", m, null, delta);
        }
    }

    private void addSignal(String rule, Models.MatchInfo m, Integer threshold, int delta5m) {
        String key = rule + ":" + m.id + (threshold == null ? "" : ":" + threshold);
        for (Models.Signal s : signals) {
            if (s.key.equals(key)) return;
        }

        Models.Signal s = new Models.Signal();
        s.key = key;
        s.rule = rule;
        s.matchId = m.id;
        s.threshold = threshold;
        s.delta5m = delta5m;
        signals.add(s);

        boolean first = seenSignalKeys.add(key);
        if (first) {
            persistSeenKeys();
            sendSignalNotification(s, m);
        }
    }

    private void cleanupSignals() {
        boolean all = StateStore.isAllMatchesMode(this);
        Iterator<Models.Signal> it = signals.iterator();
        while (it.hasNext()) {
            Models.Signal s = it.next();
            Models.MatchInfo m = matches.get(s.matchId);
            if (m == null || !m.live || !signalValid(s, m, all)) it.remove();
        }
    }

    private boolean signalValid(Models.Signal s, Models.MatchInfo m, boolean allMode) {
        if (allMode) {
            if ("ALL_15_30".equals(s.rule)) {
                return m.minute >= 15 && m.minute <= 30 && m.homeScore == 0 && m.awayScore == 0;
            }
            if ("ALL_60_82".equals(s.rule)) {
                return m.minute >= 60 && m.minute <= 82 && m.homeScore == 0 && m.awayScore == 0;
            }
            return false;
        }

        if (s.rule.startsWith("ALL_")) return false;
        if (!RuleEngine.isWatchedMatch(m)) return false;

        switch (s.rule) {
            case "DRAW_15_30": return m.minute >= 15 && m.minute <= 30 && m.homeScore == m.awayScore;
            case "LOSING_15_30": return m.minute >= 15 && m.minute <= 30 && RuleEngine.watchedTeamLosing(m);
            case "DRAW_60_82": return m.minute >= 60 && m.minute <= 82 && m.homeScore == m.awayScore;
            case "LOSING_60_82": return m.minute >= 60 && m.minute <= 82 && RuleEngine.watchedTeamLosing(m);
            case "CHECKPOINT": return m.minute >= 15 && m.minute <= 30 && m.homeScore == 0 && m.awayScore == 0;
            case "RULE2": {
                Models.StatInfo st = stats.get(m.id);
                return m.minute >= 18 && m.minute <= 30
                        && m.homeScore == 0 && m.awayScore == 0
                        && RuleEngine.inRule2League(m.league)
                        && st != null && st.homeSot != null && st.homeSot >= 2;
            }
            case "SHOT_BURST": return shotDelta5m(m.id) >= 4;
            default: return false;
        }
    }

    private void recordShotPoint(String matchId, int total) {
        long now = System.currentTimeMillis();
        List<Models.ShotPoint> list = shotHistory.computeIfAbsent(matchId, k -> new ArrayList<>());
        if (list.isEmpty() || list.get(list.size() - 1).total != total) {
            list.add(new Models.ShotPoint(now, total));
        }
        long cutoff = now - 6 * 60_000L;
        while (list.size() > 1 && list.get(0).at < cutoff) list.remove(0);
    }

    private int shotDelta5m(String matchId) {
        List<Models.ShotPoint> list = shotHistory.get(matchId);
        if (list == null || list.size() < 2) return 0;
        long cutoff = System.currentTimeMillis() - 5 * 60_000L;
        Models.ShotPoint first = null;
        Models.ShotPoint last = list.get(list.size() - 1);
        for (Models.ShotPoint p : list) {
            if (p.at >= cutoff) {
                first = p;
                break;
            }
        }
        if (first == null) first = list.get(0);
        return Math.max(0, last.total - first.total);
    }

    private void sendSignalNotification(Models.Signal s, Models.MatchInfo m) {
        Models.StatInfo st = stats.get(m.id);
        String sot = st != null && st.homeSot != null && st.awaySot != null
                ? st.homeSot + "-" + st.awaySot : "?-?";
        String big = st != null && st.homeBig != null && st.awayBig != null
                ? st.homeBig + "-" + st.awayBig : "?-?";
        String body = m.minute + "' " + m.home + " " + m.homeScore + "-" + m.awayScore + " " + m.away
                + "\n" + RuleEngine.prettyRule(s.rule)
                + "\nİS " + sot + " · Big chances " + big;

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, notificationSeq, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification n = new Notification.Builder(this, SIGNAL_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Canlı maç sinyali")
                .setContentText(m.minute + "' " + m.home + " " + m.homeScore + "-" + m.awayScore + " " + m.away)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setColor(Color.rgb(103, 208, 245))
                .build();

        getSystemService(NotificationManager.class).notify(notificationSeq++, n);
    }

    private void publishState() {
        try {
            JSONObject root = new JSONObject();
            boolean all = StateStore.isAllMatchesMode(this);
            root.put("enabled", StateStore.isEnabled(this));
            root.put("allMatchesMode", all);
            root.put("lastSnapshotAt", lastSnapshotAt);
            root.put("monitorStatus", monitorStatus());

            JSONArray mArr = new JSONArray();
            JSONArray sArr = new JSONArray();
            int candidateCount = 0;

            for (Models.MatchInfo m : matches.values()) {
                if (!m.live) continue;
                if (!all && !RuleEngine.isWatchedMatch(m)) continue;
                mArr.put(m.toJson());
                if (!all && m.minute >= 18 && m.minute <= 30 && m.homeScore == 0 && m.awayScore == 0
                        && RuleEngine.inRule2League(m.league)) {
                    candidateCount++;
                }

                Models.StatInfo st = stats.get(m.id);
                if (st != null) sArr.put(st.toJson());
            }

            JSONArray sigArr = new JSONArray();
            for (Models.Signal s : signals) {
                Models.MatchInfo m = matches.get(s.matchId);
                if (m == null) continue;
                JSONObject o = s.toJson();
                o.put("ruleName", RuleEngine.prettyRule(s.rule));
                o.put("match", m.toJson());
                Models.StatInfo st = stats.get(s.matchId);
                if (st != null) o.put("stat", st.toJson());
                sigArr.put(o);
            }

            root.put("matches", mArr);
            root.put("stats", sArr);
            root.put("signals", sigArr);
            root.put("candidateCount", candidateCount);
            root.put("currentWorkerId", currentWorkerId == null ? JSONObject.NULL : currentWorkerId);
            root.put("queueSize", queue.size());

            final String stateJson = root.toString();
            stateWriter.execute(() -> StateStore.setState(
                    getApplicationContext(),
                    stateJson
            ));
        } catch (JSONException ignored) {
        }
    }

    private String monitorStatus() {
        if (!StateStore.isEnabled(this)) return "Takip kapalı";
        if (lastSnapshotAt == 0L) {
            return lastMonitorError == null || lastMonitorError.isEmpty()
                    ? "Monitor hazırlanıyor…"
                    : "Bağlantı sorunu · yeniden deneniyor";
        }
        long sec = Math.max(0, (System.currentTimeMillis() - lastSnapshotAt) / 1000L);
        return sec <= 10 ? "Monitor aktif" : "Son veri " + sec + " sn önce";
    }

    private void restoreSeenKeys() {
        Set<String> saved = StateStore.prefs(this).getStringSet("seen_signal_keys", null);
        if (saved != null) seenSignalKeys.addAll(saved);
    }

    private void persistSeenKeys() {
        StateStore.prefs(this).edit()
                .putStringSet("seen_signal_keys", new HashSet<>(seenSignalKeys))
                .apply();
    }
}
