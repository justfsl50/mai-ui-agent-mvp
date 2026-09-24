package dev.maiui.mvp;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Accessibility service = the device harness: screenshots in, gestures/keys/app launches out. */
public class AgentService extends AccessibilityService {
    public static volatile AgentService instance;
    private static final StringBuilder LOG = new StringBuilder();
    public static volatile boolean running = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor mainExec = r -> main.post(r);
    private final AtomicBoolean stop = new AtomicBoolean(false);
    private View overlay;
    private TextView overlayText;
    private int screenW, screenH;

    public static synchronized void log(String s) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + s + "\n";
        LOG.append(line);
        if (LOG.length() > 60000) LOG.delete(0, LOG.length() - 50000);
        android.util.Log.i("MAIUI", s);
    }

    public static synchronized String logText() { return LOG.toString(); }
    public static synchronized void clearLog() { LOG.setLength(0); }

    @Override protected void onServiceConnected() { instance = this; log("Accessibility service connected"); }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
    @Override public boolean onUnbind(Intent intent) { instance = null; stop.set(true); return super.onUnbind(intent); }
    @Override public void onDestroy() { instance = null; stop.set(true); removeOverlay(); super.onDestroy(); }

    public void requestStop() { stop.set(true); log("Stop requested"); }

    // ---------------- installed apps (for `open` and the prompt's Available Apps) ----------------
    Map<String, String> launchableApps() {
        PackageManager pm = getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(i, 0);
        Map<String, String> out = new LinkedHashMap<>();
        List<String> labels = new ArrayList<>();
        Map<String, String> tmp = new LinkedHashMap<>();
        for (ResolveInfo ri : ris) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            String label = String.valueOf(ri.loadLabel(pm)).trim();
            if (label.isEmpty() || tmp.containsKey(label)) continue;
            tmp.put(label, pkg); labels.add(label);
        }
        Collections.sort(labels, String.CASE_INSENSITIVE_ORDER);
        for (String l : labels) out.put(l, tmp.get(l));
        return out;
    }

    public String appsListForPrompt() {
        StringBuilder sb = new StringBuilder("[");
        int n = 0;
        for (String l : launchableApps().keySet()) {
            if (n++ >= 120) break;
            if (n > 1) sb.append(',');
            sb.append('"').append(l.replace("\"", "'")).append('"');
        }
        return sb.append(']').toString();
    }

    // ---------------- agent loop ----------------
    public void startTask(String task, MaiAgent.Config cfg, int maxSteps, int maxSide, boolean fromHome, boolean useInstalledApps) {
        if (running) { log("A task is already running"); return; }
        running = true;
        stop.set(false);
        new Thread(() -> {
            try {
                if (useInstalledApps) cfg.appsList = appsListForPrompt();
                MaiAgent agent = new MaiAgent(cfg);
                log("TASK: " + task + "  (model=" + cfg.model + ", history_n=" + cfg.historyN + ")");
                showOverlay("Starting...");
                if (fromHome) { performGlobalAction(GLOBAL_ACTION_HOME); sleep(1200); }
                for (int step = 1; step <= maxSteps && !stop.get(); step++) {
                    setOverlayVisible(false);
                    sleep(250);
                    String b64 = screenshotPngB64(maxSide);
                    setOverlayVisible(true);
                    if (b64 == null) { log("Screenshot failed; stopping"); break; }
                    setOverlay("Step " + step + ": thinking...");
                    MaiAgent.Prediction p;
                    try { p = agent.predict(task, b64, AgentService::log); }
                    catch (Exception e) { log("Model call failed after 3 attempts: " + e.getMessage()); break; }
                    if (stop.get()) break;
                    log("STEP " + step + " THINK: " + p.thinking);
                    log("STEP " + step + " ACTION: " + MaiAgent.mem2response(agent.steps().get(agent.steps().size() - 1)).replaceAll("(?s).*<tool_call>\\n|\\n</tool_call>", ""));
                    setOverlay("Step " + step + ": " + p.action.optString("action"));
                    setOverlayVisible(false);
                    String result = execute(p.action);
                    setOverlayVisible(true);
                    if (result != null && result.startsWith("DONE")) { log(result); setOverlay(result); sleep(2500); break; }
                    if (result != null) log(result);
                    sleep(1500);
                    if (step == maxSteps) log("Reached max steps (" + maxSteps + ")");
                }
            } catch (Throwable t) {
                log("Agent crashed: " + t);
            } finally {
                running = false;
                log("Agent stopped");
                removeOverlay();
            }
        }, "mai-agent").start();
    }

    private String execute(JSONObject a) throws Exception {
        String type = a.optString("action");
        switch (type) {
            case "click": { float[] p = px(a.getJSONArray("coordinate")); return tap(p[0], p[1], 60) ? null : "click gesture was cancelled"; }
            case "long_press": { float[] p = px(a.getJSONArray("coordinate")); return tap(p[0], p[1], 1000) ? null : "long_press gesture was cancelled"; }
            case "double_click": { float[] p = px(a.getJSONArray("coordinate")); tap(p[0], p[1], 50); sleep(90); tap(p[0], p[1], 50); return null; }
            case "drag": {
                float[] s = px(a.getJSONArray("start_coordinate")), e = px(a.getJSONArray("end_coordinate"));
                return stroke(s[0], s[1], e[0], e[1], 400) ? null : "drag gesture was cancelled";
            }
            case "swipe": {
                // Same geometry as MobileWorld's controller.swipe(): finger moves in `direction`,
                // 0.4*width vertically / 0.2*width horizontally, 400 ms, from coordinate or screen centre.
                float x = screenW / 2f, y = screenH / 2f;
                if (a.has("coordinate")) { float[] p = px(a.getJSONArray("coordinate")); x = p[0]; y = p[1]; }
                float unit = 2 * (int) (screenW / 10f);
                String d = a.optString("direction", "up");
                float dx = 0, dy = 0;
                if (d.equals("up")) dy = -2 * unit; else if (d.equals("down")) dy = 2 * unit;
                else if (d.equals("left")) dx = -unit; else if (d.equals("right")) dx = unit;
                else return "unknown swipe direction " + d;
                float ex = clamp(x + dx, 1, screenW - 2), ey = clamp(y + dy, 1, screenH - 2);
                return stroke(x, y, ex, ey, 400) ? null : "swipe gesture was cancelled";
            }
            case "type": return typeText(a.optString("text", ""));
            case "open": return openApp(a.optString("text", ""));
            case "system_button": {
                String b = a.optString("button", "").toLowerCase(Locale.US);
                if (b.equals("back")) performGlobalAction(GLOBAL_ACTION_BACK);
                else if (b.equals("home")) performGlobalAction(GLOBAL_ACTION_HOME);
                else if (b.equals("menu")) performGlobalAction(GLOBAL_ACTION_RECENTS);
                else if (b.equals("enter")) return pressEnter();
                else return "unknown button " + b;
                return null;
            }
            case "wait": sleep(2000); return null;
            case "terminate": return "DONE: terminate(" + a.optString("status") + ")";
            case "answer": return "DONE: ANSWER: " + a.optString("text");
            default: return "unsupported action '" + type + "' (ignored)";
        }
    }

    private float[] px(JSONArray c) throws Exception {
        return new float[]{ clamp((float) (c.getDouble(0) * screenW), 1, screenW - 2), clamp((float) (c.getDouble(1) * screenH), 1, screenH - 2) };
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    private boolean tap(float x, float y, long ms) { return stroke(x, y, x, y, ms); }

    private boolean stroke(float x1, float y1, float x2, float y2, long ms) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, Math.max(1, ms))).build();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean ok = new AtomicBoolean(false);
        main.post(() -> dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription d) { ok.set(true); latch.countDown(); }
            @Override public void onCancelled(GestureDescription d) { latch.countDown(); }
        }, null));
        try { latch.await(ms + 5000, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
        return ok.get();
    }

    private AccessibilityNodeInfo focusedInput() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo f = root != null ? root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) : null;
        if (f == null) f = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        return f;
    }

    private String typeText(String text) {
        AccessibilityNodeInfo n = focusedInput();
        if (n == null || !n.isEditable()) return "type: no focused text field (tap a field first)";
        String cur = (n.getText() == null || n.isShowingHintText()) ? "" : n.getText().toString();
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cur + text);
        boolean ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        return ok ? null : "type: field rejected SET_TEXT";
    }

    private String pressEnter() {
        AccessibilityNodeInfo n = focusedInput();
        if (n != null && n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId())) return null;
        return "enter: no focused field accepted IME enter";
    }

    private String openApp(String name) {
        Map<String, String> apps = launchableApps();
        String q = name.trim().toLowerCase(Locale.ROOT), qn = q.replaceAll("[^a-z0-9]", "");
        String pkg = null;
        for (Map.Entry<String, String> e : apps.entrySet()) if (e.getKey().equalsIgnoreCase(name.trim()) || e.getValue().equalsIgnoreCase(name.trim())) { pkg = e.getValue(); break; }
        if (pkg == null) for (Map.Entry<String, String> e : apps.entrySet()) if (e.getKey().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "").equals(qn)) { pkg = e.getValue(); break; }
        if (pkg == null) for (Map.Entry<String, String> e : apps.entrySet()) if (e.getKey().toLowerCase(Locale.ROOT).startsWith(q)) { pkg = e.getValue(); break; }
        if (pkg == null && qn.length() >= 3) for (Map.Entry<String, String> e : apps.entrySet()) if (e.getKey().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "").contains(qn)) { pkg = e.getValue(); break; }
        if (pkg == null) return "open: no installed app matches '" + name + "'";
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return "open: " + pkg + " has no launch intent";
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(i);
        sleep(1500);
        return "opened " + pkg;
    }

    // ---------------- screenshot ----------------
    private String screenshotPngB64(int maxSide) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Bitmap> out = new AtomicReference<>();
        AtomicReference<String> err = new AtomicReference<>();
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExec, new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult r) {
                try {
                    HardwareBuffer hb = r.getHardwareBuffer();
                    Bitmap hw = Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
                    out.set(hw.copy(Bitmap.Config.ARGB_8888, false));
                    hw.recycle(); hb.close();
                } catch (Throwable t) { err.set(t.toString()); }
                latch.countDown();
            }
            @Override public void onFailure(int code) { err.set("error code " + code); latch.countDown(); }
        });
        try { latch.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        Bitmap bmp = out.get();
        if (bmp == null) { log("takeScreenshot failed: " + err.get()); return null; }
        screenW = bmp.getWidth(); screenH = bmp.getHeight();
        Bitmap send = bmp;
        int longSide = Math.max(screenW, screenH);
        if (maxSide > 0 && longSide > maxSide) {
            float s = maxSide / (float) longSide;
            send = Bitmap.createScaledBitmap(bmp, Math.round(screenW * s), Math.round(screenH * s), true);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        send.compress(Bitmap.CompressFormat.PNG, 100, bos);
        if (send != bmp) send.recycle();
        bmp.recycle();
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }

    // ---------------- small status overlay with Stop button ----------------
    private void showOverlay(String text) {
        main.post(() -> {
            if (overlay != null) return;
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setBackgroundColor(Color.argb(220, 30, 20, 60));
            box.setPadding(24, 12, 12, 12);
            overlayText = new TextView(this);
            overlayText.setTextColor(Color.WHITE);
            overlayText.setTextSize(13);
            overlayText.setText(text);
            overlayText.setMaxWidth(700);
            Button b = new Button(this);
            b.setText("Stop");
            b.setOnClickListener(v -> requestStop());
            box.addView(overlayText);
            box.addView(b);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.y = 220;
            wm.addView(box, lp);
            overlay = box;
        });
    }

    private void setOverlay(String text) { main.post(() -> { if (overlayText != null) overlayText.setText(text); }); }

    private void setOverlayVisible(boolean v) {
        CountDownLatch l = new CountDownLatch(1);
        main.post(() -> { if (overlay != null) overlay.setVisibility(v ? View.VISIBLE : View.GONE); l.countDown(); });
        try { l.await(1, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        if (!v) sleep(120);
    }

    private void removeOverlay() {
        main.post(() -> {
            if (overlay != null) {
                try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(overlay); } catch (Throwable ignored) { }
                overlay = null; overlayText = null;
            }
        });
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }
}
