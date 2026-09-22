# SponsorBlock YouTube Vanced Implementation
In order to use this in YouTube/Vanced you must first apply the smali mods applied to vanced (the patching process used for this is currently automated using our closed source tools with no plans to open source it for the time being) (if you mod vanced directly it is not required)
* First make your edits in android studio
* Change the string "replaceMeWithsetMillisecondMethod" on PlayerController.java to the method name of YouTube package
* Compile debug apk
* Decompile this apk using apktool https://github.com/iBotPeaches/Apktool
* Take this decompiled folder and look for a folder labeled pl in one of your dex class folders
* Decompile YouTube/Vanced using apktool (you only need to decompile the base apk files (for vanced you can get these using vanced manager and looking in android/data/com.vanced.manager for black or dark.apk), if you are decompiling stock youtube you must also merge a dpi split into it (todo))
* Copy the pl folder from earlier into the dex class folder (remove any existing one completely first)
* Recompile your modded YouTube/Vanced using apktool and sign it + all splits required for your device using the same key

## Music remover
Removes background music from videos while keeping voices. It is turned off by default and can be turned on from its settings screen or with the "No music" button under the player.

The audio is processed on the device in real time (`fi.vanced.libraries.youtube.musicremover`), no network or native libraries are needed. Every frequency band of the audio is attenuated based on three cues: voices are mixed in the center of the stereo image while music is usually wide, sustained tones (chords, pads) are steadier than speech, and bass/cymbals lie outside of the speech range. The strength (low/medium/high) trades how much music is removed against how natural voices sound. Music that is mixed exactly like the voice, for example the instruments of a song, can only be partially removed.

The following hooks have to be added by the patch:
* **Audio**: add an audio processor to the chain of ExoPlayer's `DefaultAudioSink`, before the `SonicAudioProcessor` (playback speed). As YouTube's ExoPlayer is obfuscated, this is a small adapter class implementing YouTube's `AudioProcessor` interface that delegates every call to `MusicRemovalAudioProcessor`:
  ```java
  public AudioFormat configure(AudioFormat input) {
      return processor.configure(input.sampleRate, input.channelCount, input.encoding) ? input : AudioFormat.NOT_SET;
  }
  public boolean isActive() { return processor.isActive(); }
  public void queueInput(ByteBuffer buffer) { processor.queueInput(buffer); }
  public void queueEndOfStream() { processor.queueEndOfStream(); }
  public ByteBuffer getOutput() { return processor.getOutput(); }
  public boolean isEnded() { return processor.isEnded(); }
  public void flush() { processor.flush(); }
  public void reset() { processor.reset(); }
  ```
  The processor only becomes active while the music remover is turned on, so it costs nothing otherwise. While active it can be switched on and off instantly. It supports 16 bit and float PCM in mono and stereo, and keeps audio and video in sync.
* **Settings**: add a preference that opens `MusicRemoverFragment` (like `RYDFragment`), using the strings `vanced_music_remover_settings_title` and `vanced_music_remover_settings_summary`.
* The button under the player is created by `SlimButtonContainer` and uses the drawables `vanced_yt_music_remover_on` and `vanced_yt_music_remover_off`.

Run the unit tests with `./gradlew testDebugUnitTest`.

### بالعربية
ميزة «إزالة الموسيقى» تزيل الموسيقى الخلفية من الفيديوهات مع الإبقاء على الأصوات البشرية. تُعالَج الصوتيات على الجهاز مباشرة أثناء التشغيل، ويمكن تشغيلها من شاشة الإعدادات الخاصة بها أو من زر «بدون موسيقى» أسفل المشغّل، مع ثلاث درجات لقوة الإزالة (منخفضة، متوسطة، عالية).
