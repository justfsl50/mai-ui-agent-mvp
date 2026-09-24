# MAI-UI Agent (Android MVP)

Java port of the MAI-UI navigation agent (github.com/Tongyi-MAI/MAI-UI, src/mai_naivigation_agent.py, Apache-2.0)
running on the phone as an Accessibility Service: screenshot -> MAI-UI model (OpenAI-compatible server) -> tap / swipe / type / open app.
Message building matches all 4 upstream test fixtures (tests/output_messages) exactly.

## Model server (pick one)
A. GPU box / cloud GPU (upstream-recommended, best quality):
   pip install vllm==0.11.0
   python -m vllm.entrypoints.openai.api_server --model Tongyi-MAI/MAI-UI-8B --served-model-name MAI-UI-8B --host 0.0.0.0 --port 8000 --trust-remote-code
   App URL: http://<server-ip>:8000/v1   Model: MAI-UI-8B
B. Laptop CPU/GPU with llama.cpp (github.com/ggml-org/llama.cpp/releases):
   llama-server -m MAI-UI-2B.Q4_K_M.gguf --mmproj MAI-UI-2B.mmproj-Q8_0.gguf --host 0.0.0.0 --port 8080 -c 8192 --alias MAI-UI-2B --image-min-tokens 1024
   (GGUFs: huggingface.co/mradermacher/MAI-UI-2B-GGUF; 8B: huggingface.co/mradermacher/MAI-UI-8B-GGUF)
   App URL: http://<laptop-LAN-ip>:8080/v1   Model: MAI-UI-2B
C. Fully on the phone (experimental, needs ~6-8 GB RAM, slow): run the same llama-server from the
   official llama-<ver>-bin-android-arm64.tar.gz inside Termux, then use http://127.0.0.1:8080/v1 and set
   "Max screenshot long side" to ~960.

## Install
1. Download MAI-UI-Agent.apk from this repo's Releases page (v0.1) on your phone and open it (allow "install unknown apps"), or: adb install MAI-UI-Agent.apk
2. Android 13+: Settings > Apps > MAI-UI Agent > (three-dot menu) > "Allow restricted settings".
3. In the app tap "Enable accessibility service" > Installed apps > MAI-UI Agent > On.
4. Enter server URL + model name, tap "Test connection", type a task, tap Start. Stop from the floating bar or the app.
Requires Android 11+ (API 30, for accessibility screenshots).

## Build from source
./build.sh  (needs JDK 17 + Android build-tools 34 + platform android-34; no Gradle)
