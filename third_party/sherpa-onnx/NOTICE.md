# On-device speech engines

Eta ships two pinned JNI runtimes so the user can pick a speech model at runtime. No
model weights are bundled; the selected pack is downloaded and verified on demand.

## sherpa-ncnn (ncnn / pnnx)

- Kotlin JNI binding: k2-fsa/sherpa-ncnn tag v2.1.15,
  `android/SherpaNcnn/app/src/main/java/com/k2fsa/sherpa/ncnn/SherpaNcnn.kt`.
  Apache-2.0, see LICENSE. Eta keeps JNI package/config fields unchanged, trims demo
  helpers, adds deterministic AutoCloseable cleanup and checks for a null native handle.
- Native binaries: official v2.1.15 Android release. URL and SHA-256 are pinned in
  `scripts/prepare-speech-runtime.py`. Only explicit ABI/library entries are copied;
  Gradle downloads/validates these during builds. JNI names/config fields are kept by R8.
  ARM64 LOAD segments have 16-KiB alignment. CPU decoding only; no GPU selection.
- Engine family: streaming Zipformer transducer (`DecoderConfig.method = greedy_search`).

## sherpa-onnx (ONNX Runtime)

- Kotlin JNI binding: k2-fsa/sherpa-onnx tag v1.13.8, upstream directory
  `sherpa-onnx/kotlin-api/` (22 files, ~150 KB), vendored verbatim into
  `app/src/main/kotlin/com/k2fsa/sherpa/onnx/`. Apache-2.0, see LICENSE. Package name,
  class names and JNI-declared config fields are kept byte-exact so the pinned native
  library resolves them; R8 keeps the whole package (see `proguard-rules.pro`).
  `OnlineRecognizer`, `OfflineRecognizer` (SenseVoice) and `Vad` are used; the remaining
  API surface (TTS, diarization, tagging) is compiled but stripped by R8 in release builds.
- Native binaries: official v1.13.8 Android release, `sherpa-onnx-v1.13.8-android.tar.bz2`
  (46093321 bytes, SHA-256 `2ff63469…6b4f44`). The archive ships `libsherpa-onnx-jni.so`
  and `libonnxruntime.so` per ABI; both are pinned in `scripts/prepare-speech-runtime.py`
  and validated by Gradle. ONNX Runtime is MIT, see LICENSE-onnxruntime.
- Engine families: streaming Zipformer2 via `OnlineRecognizer` (chunk-based online decode)
  and non-streaming SenseVoice via `OfflineRecognizer`, segmented by Silero VAD.

## Models

- `sherpa-ncnn-streaming-zipformer-bilingual-zh-en-2023-02-13` (engine: sherpa-ncnn),
  Hugging Face revision 05945efc40afe4b572542f01104ca5c413a9f6e1. No ModelScope mirror.
- `X-ASR-zh-en` chunk-480ms (engine: sherpa-onnx), Hugging Face revision
  689ff18c584d29910da37b6fe904db0c1489c9d1 and the ModelScope mirror owned by
  `Gilgamesh-J`. Immutable URLs, per-file lengths and SHA-256 values are recorded in
  `SpeechModelCatalog`; the source picker chooses between Hugging Face and ModelScope.
  Non-LFS text files carry a length-only check where no upstream hash exists.
- `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09` (engine: sherpa-onnx offline),
  Hugging Face revision 355f4d4884d8afd08aef04b9007a8556d7b463b2 and a ModelScope mirror
  whose files match the official SHA-256 values. The pack also downloads
  `silero_vad.onnx` (643854 bytes, SHA-256 `9e2449e1…cb1fd6`) from a pinned ModelScope
  mirror of the official sherpa-onnx VAD file. Silero VAD is MIT-licensed.
- Weights are NOT in git or the APK. Weight licensing is not declared by upstream model
  metadata; the engine's Apache-2.0/MIT license does not grant model rights. The download
  confirmation discloses this limitation.

## Behaviour

- No Operit application source is included. Silero VAD segments the offline SenseVoice
  utterance; streaming models use their own trailing-silence endpoint detector.
- User opt-in only. A speech pack is verified in private no-backup storage; microphone
  permission is requested at explicit tap. Download does not enable speech input.
- Incremental or final results update only the captured draft selection or the active
  voice-conversation transcript. User edits/send stop capture instead of overwriting text.
  Second tap, lifecycle STOP/disposal, drawer, edit mode and new generation cancel
  recording. No audio files, raw transcripts in logs, or audio network requests.
- Initial no-text deadline: 8 s; maximum one capture: 60 s. Model loading occurs before
  capture on an IO worker. The native recognizer is closed when each capture ends.
- Engine names and models are separate from the assistant/chat model configuration.
