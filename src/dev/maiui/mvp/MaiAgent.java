package dev.maiui.mvp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java port of MAIUINaivigationAgent (Tongyi-MAI/MAI-UI, src/mai_naivigation_agent.py, Apache-2.0).
 * Same system prompt, same message layout (history_n screenshots + all past assistant turns),
 * same sampling params, same <thinking>/<tool_call> parsing and 0..999 coordinate scale.
 */
public class MaiAgent {
    public static final double SCALE_FACTOR = 999.0;

    public static final String DEFAULT_APPS =
            "[\"Camera\",\"Chrome\",\"Clock\",\"Contacts\",\"Dialer\",\"Files\",\"Settings\",\"Markor\",\"Tasks\",\"Simple Draw Pro\",\"Simple Gallery Pro\",\"Simple SMS Messenger\",\"Audio Recorder\",\"Pro Expense\",\"Broccoli APP\",\"OSMand\",\"VLC\",\"Joplin\",\"Retro Music\",\"OpenTracks\",\"Simple Calendar Pro\"]";

    // Verbatim MAI_MOBILE_SYS_PROMPT from src/prompt.py; only the Available Apps list is substitutable.
    public static String systemPrompt(String appsList) {
        return "You are a GUI agent. You are given a task and your action history, with screenshots. You need to perform the next action to complete the task.\n"
                + "\n"
                + "## Output Format\n"
                + "For each function call, return the thinking process in <thinking> </thinking> tags, and a json object with function name and arguments within <tool_call></tool_call> XML tags:\n"
                + "```\n"
                + "<thinking>\n"
                + "...\n"
                + "</thinking>\n"
                + "<tool_call>\n"
                + "{\"name\": \"mobile_use\", \"arguments\": <args-json-object>}\n"
                + "</tool_call>\n"
                + "```\n"
                + "\n"
                + "## Action Space\n"
                + "\n"
                + "{\"action\": \"click\", \"coordinate\": [x, y]}\n"
                + "{\"action\": \"long_press\", \"coordinate\": [x, y]}\n"
                + "{\"action\": \"type\", \"text\": \"\"}\n"
                + "{\"action\": \"swipe\", \"direction\": \"up or down or left or right\", \"coordinate\": [x, y]} # \"coordinate\" is optional. Use the \"coordinate\" if you want to swipe a specific UI element.\n"
                + "{\"action\": \"open\", \"text\": \"app_name\"}\n"
                + "{\"action\": \"drag\", \"start_coordinate\": [x1, y1], \"end_coordinate\": [x2, y2]}\n"
                + "{\"action\": \"system_button\", \"button\": \"button_name\"} # Options: back, home, menu, enter\n"
                + "{\"action\": \"wait\"}\n"
                + "{\"action\": \"terminate\", \"status\": \"success or fail\"}\n"
                + "{\"action\": \"answer\", \"text\": \"xxx\"} # Use escape characters \\', \\\", and \\n in text part to ensure we can parse the text in normal python string format.\n"
                + "\n"
                + "\n"
                + "## Note\n"
                + "- Write a small plan and finally summarize your next action (with its target element) in one sentence in <thinking></thinking> part.\n"
                + "- Available Apps: `" + appsList + "`.\n"
                + "You should use the `open` action to open the app as possible as you can, because it is the fast way to open the app.\n"
                + "- You must follow the Action Space strictly, and return the correct json object within <thinking> </thinking> and <tool_call></tool_call> XML tags.";
    }

    public static class Config {
        public String baseUrl;
        public String model;
        public String apiKey;
        public int historyN = 3;
        public int maxTokens = 2048;
        public String appsList = DEFAULT_APPS;
        public int maxAttempts = 3;
    }

    public static class Step {
        public final String thinking;
        public final JSONObject action; // normalized coords (0..1), like structured_action["action_json"]
        public final String screenshotB64;
        public final List<String> keyOrder; // argument key order as emitted by the model
        Step(String thinking, JSONObject action, String screenshotB64, List<String> keyOrder) {
            this.thinking = thinking; this.action = action; this.screenshotB64 = screenshotB64; this.keyOrder = keyOrder;
        }
    }

    public static class Prediction {
        public String raw;
        public String thinking;
        public JSONObject action;
        public List<String> keyOrder;
    }

    private final Config cfg;
    private final List<Step> steps = new ArrayList<>();

    public MaiAgent(Config cfg) { this.cfg = cfg; }

    public List<Step> steps() { return steps; }

    // ---- message building (mirrors _prepare_images + _build_messages) ----
    JSONArray buildMessages(String instruction, String currentB64) throws Exception {
        List<String> images = new ArrayList<>();
        int maxHistory = Math.min(steps.size(), cfg.historyN - 1);
        if (maxHistory > 0) {
            for (int i = steps.size() - maxHistory; i < steps.size(); i++) images.add(steps.get(i).screenshotB64);
        }
        images.add(currentB64);

        JSONArray messages = new JSONArray();
        messages.put(textMsg("system", systemPrompt(cfg.appsList)));
        messages.put(textMsg("user", instruction));

        int imageNum = 0;
        if (steps.size() > 0) {
            int startImageIdx = Math.max(0, steps.size() - (cfg.historyN - 1));
            for (int h = 0; h < steps.size(); h++) {
                if (h >= startImageIdx) {
                    if (imageNum < images.size() - 1) messages.put(imageMsg(images.get(imageNum)));
                    imageNum++;
                }
                messages.put(textMsg("assistant", mem2response(steps.get(h))));
            }
            if (imageNum < images.size()) messages.put(imageMsg(images.get(imageNum)));
        } else {
            messages.put(imageMsg(images.get(0)));
        }
        return messages;
    }

    private static JSONObject textMsg(String role, String text) throws Exception {
        JSONObject c = new JSONObject().put("type", "text").put("text", text);
        return new JSONObject().put("role", role).put("content", new JSONArray().put(c));
    }

    private static JSONObject imageMsg(String b64) throws Exception {
        JSONObject c = new JSONObject().put("type", "image_url")
                .put("image_url", new JSONObject().put("url", "data:image/png;base64," + b64));
        return new JSONObject().put("role", "user").put("content", new JSONArray().put(c));
    }

    // mem2response: only "coordinate" is converted back to the 0..999 scale (same as upstream).
    static String mem2response(Step s) throws Exception {
        JSONObject a = new JSONObject(s.action.toString());
        if (a.has("coordinate")) {
            JSONArray c = a.getJSONArray("coordinate");
            double px, py;
            if (c.length() == 2) { px = c.getDouble(0); py = c.getDouble(1); }
            else { px = (c.getDouble(0) + c.getDouble(2)) / 2; py = (c.getDouble(1) + c.getDouble(3)) / 2; }
            a.put("coordinate", new JSONArray().put((long) (px * SCALE_FACTOR)).put((long) (py * SCALE_FACTOR)));
        }
        StringBuilder sb = new StringBuilder("{\"name\":\"mobile_use\",\"arguments\":");
        pyDumpOrdered(a, s.keyOrder, sb);
        sb.append('}');
        return "<thinking>\n" + s.thinking + "\n</thinking>\n<tool_call>\n" + sb + "\n</tool_call>";
    }

    static void pyDumpOrdered(JSONObject a, List<String> order, StringBuilder sb) throws Exception {
        if (order == null) { pyDump(a, sb); return; }
        sb.append('{'); boolean first = true;
        List<String> keys = new ArrayList<>();
        for (String k : order) if (a.has(k) && !keys.contains(k)) keys.add(k);
        for (Iterator<String> it = a.keys(); it.hasNext(); ) { String k = it.next(); if (!keys.contains(k)) keys.add(k); }
        for (String k : keys) {
            if (!first) sb.append(','); first = false;
            pyStr(k, sb); sb.append(':'); pyDump(a.get(k), sb);
        }
        sb.append('}');
    }

    // json.dumps(obj, separators=(",", ":")) with Python's default ensure_ascii=True.
    static void pyDump(Object o, StringBuilder sb) throws Exception {
        if (o == null || o == JSONObject.NULL) { sb.append("null"); }
        else if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o; sb.append('{'); boolean first = true;
            for (Iterator<String> it = j.keys(); it.hasNext(); ) {
                String k = it.next(); if (!first) sb.append(','); first = false;
                pyStr(k, sb); sb.append(':'); pyDump(j.get(k), sb);
            }
            sb.append('}');
        } else if (o instanceof JSONArray) {
            JSONArray j = (JSONArray) o; sb.append('[');
            for (int i = 0; i < j.length(); i++) { if (i > 0) sb.append(','); pyDump(j.get(i), sb); }
            sb.append(']');
        } else if (o instanceof String) { pyStr((String) o, sb); }
        else if (o instanceof Boolean) { sb.append(((Boolean) o) ? "true" : "false"); }
        else if (o instanceof Integer || o instanceof Long) { sb.append(o.toString()); }
        else if (o instanceof Number) {
            double d = ((Number) o).doubleValue();
            String r = Double.toString(d);
            if (r.contains("E")) r = new BigDecimal(r).toPlainString();
            sb.append(r);
        } else { pyStr(o.toString(), sb); }
    }

    static void pyStr(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (ch < 0x20 || ch > 0x7e) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
            }
        }
        sb.append('"');
    }

    // ---- parsing (mirrors parse_tagged_text + parse_action_to_structure_output) ----
    private static final Pattern TAGGED = Pattern.compile("<thinking>(.*?)</thinking>.*?<tool_call>(.*?)</tool_call>", Pattern.DOTALL);
    private static final Pattern TOOL_ONLY = Pattern.compile("<tool_call>(.*?)</tool_call>", Pattern.DOTALL);

    static Prediction parse(String text) throws Exception {
        text = text.trim();
        if (text.contains("</think>") && !text.contains("</thinking>")) {
            text = "<thinking>" + text.replace("</think>", "</thinking>");
        }
        String thinking = null, tool = null;
        Matcher m = TAGGED.matcher(text);
        if (m.find()) {
            thinking = stripQuotes(m.group(1).trim());
            tool = stripQuotes(m.group(2).trim());
        } else {
            // Lenient fallback (not in upstream): accept a bare <tool_call> without <thinking>.
            Matcher t = TOOL_ONLY.matcher(text);
            if (t.find()) { thinking = ""; tool = stripQuotes(t.group(1).trim()); }
        }
        if (tool == null) throw new IllegalStateException("No <tool_call> in model output");
        JSONObject call = new JSONObject(tool);
        JSONObject action = call.getJSONObject("arguments");
        for (String key : new String[]{"coordinate", "start_coordinate", "end_coordinate"}) {
            if (!action.has(key)) continue;
            JSONArray c = action.getJSONArray(key);
            double px, py;
            if (c.length() == 2) { px = c.getDouble(0); py = c.getDouble(1); }
            else if (c.length() == 4) { px = (c.getDouble(0) + c.getDouble(2)) / 2; py = (c.getDouble(1) + c.getDouble(3)) / 2; }
            else throw new IllegalStateException("Invalid coordinate format: " + c);
            action.put(key, new JSONArray().put(px / SCALE_FACTOR).put(py / SCALE_FACTOR));
        }
        Prediction p = new Prediction();
        p.raw = text; p.thinking = thinking; p.action = action; p.keyOrder = MiniJson.argumentKeyOrder(tool);
        return p;
    }

    private static String stripQuotes(String s) {
        int a = 0, b = s.length();
        while (a < b && s.charAt(a) == '"') a++;
        while (b > a && s.charAt(b - 1) == '"') b--;
        return s.substring(a, b);
    }

    // ---- predict (mirrors predict(): 3 retries, same sampling params) ----
    public Prediction predict(String instruction, String screenshotB64, Logger log) throws Exception {
        JSONArray messages = buildMessages(instruction, screenshotB64);
        JSONObject body = new JSONObject()
                .put("model", cfg.model)
                .put("messages", messages)
                .put("max_tokens", cfg.maxTokens)
                .put("temperature", 0.0)
                .put("top_p", 1.0)
                .put("frequency_penalty", 0.0)
                .put("presence_penalty", 0.0)
                .put("repetition_penalty", 1.0)
                .put("top_k", -1)
                .put("seed", 42);
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        Exception last = null;
        for (int attempt = 1; attempt <= cfg.maxAttempts; attempt++) {
            String content = null;
            try {
                long t0 = System.currentTimeMillis();
                String resp = http("POST", endpoint("/chat/completions"), payload);
                JSONObject r = new JSONObject(resp);
                JSONObject msg = r.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
                content = messageText(msg);
                log.log("Model replied in " + (System.currentTimeMillis() - t0) / 1000.0 + "s (" + payload.length / 1024 + " KB request)");
                Prediction p = parse(content);
                steps.add(new Step(p.thinking, new JSONObject(p.action.toString()), screenshotB64, p.keyOrder));
                return p;
            } catch (Exception e) {
                last = e;
                log.log("Attempt " + attempt + " failed: " + e.getMessage() + (content != null ? "\n  raw model output: " + (content.length() > 600 ? content.substring(0, 600) + "..." : content) : ""));
            }
        }
        throw last;
    }

    /**
     * Upstream reads message.content verbatim (vLLM without a tool parser). Some servers (llama-server with
     * --jinja, vLLM with --enable-auto-tool-choice) lift <tool_call> blocks into message.tool_calls and
     * <think> into reasoning_content; stitch them back into the tagged text the upstream parser expects.
     */
    static String messageText(JSONObject msg) throws Exception {
        String content = msg.isNull("content") ? "" : msg.optString("content", "");
        String reasoning = msg.optString("reasoning_content", "");
        if (!reasoning.isEmpty() && !content.contains("<thinking>") && !content.contains("</think>")) {
            content = "<thinking>\n" + reasoning.trim() + "\n</thinking>\n" + content;
        }
        JSONArray tcs = msg.optJSONArray("tool_calls");
        if (tcs != null && tcs.length() > 0 && !content.contains("<tool_call>")) {
            JSONObject fn = tcs.getJSONObject(0).getJSONObject("function");
            Object args = fn.opt("arguments");
            String argStr = args instanceof String ? (String) args : String.valueOf(args);
            content = content + "\n<tool_call>\n{\"name\": " + JSONObject.quote(fn.optString("name", "mobile_use")) + ", \"arguments\": " + argStr + "}\n</tool_call>";
        }
        if (content.trim().isEmpty()) throw new IllegalStateException("Empty model message: " + msg.toString());
        return content.trim();
    }

    String endpoint(String path) {
        String b = cfg.baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b + path;
    }

    public String listModels() throws Exception { return http("GET", endpoint("/models"), null); }

    String http(String method, String url, byte[] body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(600000);
        String key = (cfg.apiKey == null || cfg.apiKey.trim().isEmpty()) ? "empty" : cfg.apiKey.trim();
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Accept", "application/json");
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = c.getOutputStream()) { os.write(body); }
        }
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (is != null) { byte[] buf = new byte[8192]; int n; while ((n = is.read(buf)) > 0) bos.write(buf, 0, n); is.close(); }
        String s = bos.toString("UTF-8");
        c.disconnect();
        if (code >= 400) throw new IllegalStateException("HTTP " + code + ": " + (s.length() > 300 ? s.substring(0, 300) : s));
        return s;
    }

    public interface Logger { void log(String line); }
}
