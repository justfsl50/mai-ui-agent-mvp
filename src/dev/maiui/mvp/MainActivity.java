package dev.maiui.mvp;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private EditText url, model, key, historyN, maxSteps, maxSide, task;
    private CheckBox fromHome, installedApps;
    private TextView status, logView;
    private final Handler h = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            boolean on = AgentService.instance != null;
            status.setText(on ? (AgentService.running ? "Accessibility: ON  |  Agent: RUNNING" : "Accessibility: ON  |  Agent: idle")
                    : "Accessibility: OFF - tap 'Enable accessibility service' first");
            status.setTextColor(on ? Color.rgb(0, 130, 60) : Color.rgb(190, 30, 30));
            String l = AgentService.logText();
            if (!l.contentEquals(logView.getText())) logView.setText(l);
            h.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(40, 60, 40, 60);
        sv.addView(col);

        TextView title = new TextView(this);
        title.setText("MAI-UI Agent (MVP)");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);
        TextView sub = new TextView(this);
        sub.setText("Runs the Tongyi-MAI MAI-UI navigation loop on this phone: screenshot -> MAI-UI model (any OpenAI-compatible server: vLLM, llama-server on your PC, or llama-server in Termux on this phone) -> tap/swipe/type.");
        sub.setTextSize(12);
        col.addView(sub);

        status = new TextView(this);
        status.setPadding(0, 24, 0, 8);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(status);

        Button acc = button(col, "Enable accessibility service");
        acc.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        url = field(col, "Server base URL (OpenAI-compatible, ends in /v1)", "url", "http://192.168.1.10:8080/v1", InputType.TYPE_TEXT_VARIATION_URI);
        model = field(col, "Model name (as served)", "model", "MAI-UI-2B", InputType.TYPE_CLASS_TEXT);
        key = field(col, "API key (optional)", "key", "", InputType.TYPE_TEXT_VARIATION_PASSWORD);
        historyN = field(col, "history_n (screenshots in context, upstream default 3)", "historyN", "3", InputType.TYPE_CLASS_NUMBER);
        maxSteps = field(col, "Max steps", "maxSteps", "25", InputType.TYPE_CLASS_NUMBER);
        maxSide = field(col, "Max screenshot long side in px (0 = full res; lower = faster on-device)", "maxSide", "1280", InputType.TYPE_CLASS_NUMBER);
        fromHome = check(col, "Start from the Home screen", "fromHome", true);
        installedApps = check(col, "Tell the model this phone's installed apps (else upstream AndroidWorld list)", "installedApps", true);

        Button test = button(col, "Test connection (GET /models)");
        test.setOnClickListener(v -> testConnection());

        task = field(col, "Task", "task", "Open Settings and turn on Dark theme", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        task.setMinLines(2);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        col.addView(row);
        Button start = new Button(this); start.setText("Start"); row.addView(start, new LinearLayout.LayoutParams(0, -2, 1));
        Button stop = new Button(this); stop.setText("Stop"); row.addView(stop, new LinearLayout.LayoutParams(0, -2, 1));
        Button clear = new Button(this); clear.setText("Clear log"); row.addView(clear, new LinearLayout.LayoutParams(0, -2, 1));
        start.setOnClickListener(v -> startAgent());
        stop.setOnClickListener(v -> { if (AgentService.instance != null) AgentService.instance.requestStop(); });
        clear.setOnClickListener(v -> AgentService.clearLog());

        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(11);
        logView.setTextIsSelectable(true);
        logView.setPadding(0, 24, 0, 0);
        col.addView(logView);
        setContentView(sv);
    }

    @Override protected void onResume() { super.onResume(); h.post(refresher); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(refresher); save(); }

    private MaiAgent.Config config() {
        save();
        MaiAgent.Config c = new MaiAgent.Config();
        c.baseUrl = url.getText().toString().trim();
        c.model = model.getText().toString().trim();
        c.apiKey = key.getText().toString();
        c.historyN = Math.max(1, num(historyN, 3));
        return c;
    }

    private void startAgent() {
        AgentService s = AgentService.instance;
        if (s == null) { AgentService.log("Enable the MAI-UI Agent accessibility service first"); return; }
        String t = task.getText().toString().trim();
        if (t.isEmpty()) { AgentService.log("Enter a task"); return; }
        s.startTask(t, config(), Math.max(1, num(maxSteps, 25)), Math.max(0, num(maxSide, 1280)), fromHome.isChecked(), installedApps.isChecked());
        if (!fromHome.isChecked()) moveTaskToBack(true);
    }

    private void testConnection() {
        MaiAgent.Config c = config();
        AgentService.log("Testing " + c.baseUrl + "/models ...");
        new Thread(() -> {
            try {
                String r = new MaiAgent(c).listModels();
                AgentService.log("OK: " + (r.length() > 400 ? r.substring(0, 400) + "..." : r));
            } catch (Exception e) {
                AgentService.log("Connection failed: " + e);
            }
        }).start();
    }

    private int num(EditText e, int d) { try { return Integer.parseInt(e.getText().toString().trim()); } catch (Exception x) { return d; } }

    private void save() {
        if (url == null) return;
        prefs.edit().putString("url", url.getText().toString()).putString("model", model.getText().toString())
                .putString("key", key.getText().toString()).putString("historyN", historyN.getText().toString())
                .putString("maxSteps", maxSteps.getText().toString()).putString("maxSide", maxSide.getText().toString())
                .putString("task", task.getText().toString()).putBoolean("fromHome", fromHome.isChecked())
                .putBoolean("installedApps", installedApps.isChecked()).apply();
    }

    private Button button(LinearLayout col, String text) { Button b = new Button(this); b.setText(text); col.addView(b); return b; }

    private EditText field(LinearLayout col, String label, String pref, String def, int type) {
        TextView l = new TextView(this); l.setText(label); l.setTextSize(12); l.setPadding(0, 18, 0, 0); col.addView(l);
        EditText e = new EditText(this);
        if (type == InputType.TYPE_TEXT_VARIATION_URI || type == InputType.TYPE_TEXT_VARIATION_PASSWORD) type |= InputType.TYPE_CLASS_TEXT;
        e.setInputType(type);
        e.setText(prefs.getString(pref, def));
        e.setSingleLine((type & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        col.addView(e);
        return e;
    }

    private CheckBox check(LinearLayout col, String label, String pref, boolean def) {
        CheckBox c = new CheckBox(this); c.setText(label); c.setChecked(prefs.getBoolean(pref, def)); col.addView(c); return c;
    }
}
