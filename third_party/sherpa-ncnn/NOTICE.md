# Offline speech

- Kotlin JNI binding: k2-fsa/sherpa-ncnn tag v2.1.15,
  `android/SherpaNcnn/app/src/main/java/com/k2fsa/sherpa/ncnn/SherpaNcnn.kt`.
  Apache-2.0, see LICENSE. Eta keeps JNI package/config fields unchanged, trims demo
  helpers, adds deterministic AutoCloseable cleanup and checks for a null native handle.
- Native binaries: official v2.1.15 Android release. URL and SHA-256 are pinned in
  `scripts/prepare-speech-runtime.py`. Only explicit ABI/library entries are copied;
  Gradle downloads/validates these during builds. JNI names/config fields are kept by R8.
  ARM64 LOAD segments have 16-KiB alignment. CPU decoding only; no GPU selection.
- Models: the streaming Zipformer bilingual zh-en 2023-02-13 pack (Hugging Face revision
  05945efc40afe4b572542f01104ca5c413a9f6e1) plus the sherpa-onnx models documented in
  `third_party/sherpa-onnx/NOTICE.md`. Immutable URLs, lengths and SHA-256 hashes are
  recorded in SpeechModelCatalog; the user picks model and download source in settings.
  Weights are NOT in git or APK. Weight licensing is not declared by upstream metadata;
  the engine Apache license does not grant model rights. The download confirmation
  discloses this limitation.
- No Operit application source is included. The streaming models use their own endpoint
  detector (1.2 s trailing silence); the SenseVoice pack uses Silero VAD, downloaded with
  the model (see `third_party/sherpa-onnx/NOTICE.md`).
- User opt-in only. Speech pack is verified in private no-backup storage; microphone
  permission is requested at explicit tap. Download does not enable speech input.
- Incremental results update only the captured draft selection. User edits/send stop
  capture instead of overwriting text. Second tap, lifecycle STOP/disposal, drawer,
  edit mode and new generation cancel recording. No automatic sending, audio files,
  transcripts in logs, or audio network requests.
- Initial no-text deadline: 8 s; maximum one capture: 60 s. Model loading occurs before
  capture on an IO worker. Native decoder is closed when each capture ends.
- Engine names and models are separate from the assistant/chat model configuration.
