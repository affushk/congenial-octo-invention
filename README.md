# Altaf Sound Amplifier

Native Android sound-amplifier project focused on low-latency live listening through wired or Bluetooth headphones.

## Included
- Real-time microphone → headphone audio
- Adjustable gain / microphone boost
- Noise reduction and Voice Focus
- 5-band EQ controls
- Left / right balance
- Conversation, TV, Outdoor and Quiet Room presets
- Live input level meter
- Detected audio-output status
- AMOLED-first UI
- Conservative default gain, high-gain warning and Safe Reset
- GitHub Actions build that produces a debug APK artifact

## Build
The repository includes a GitHub Actions workflow. Open **Actions → Build Android APK → Run workflow** (or push a commit). When the build finishes, download the **Altaf-Sound-Amplifier-debug** artifact.

For local development, open the repository in Android Studio and let Gradle sync.

## Safety
Use headphones/earbuds. Live microphone playback through the phone speaker can create loud acoustic feedback. Start at low volume and increase gradually. This app is not a medical device and does not replace a hearing assessment or prescribed hearing aid.
