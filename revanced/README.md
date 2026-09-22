# Music remover for ReVanced

A [ReVanced](https://revanced.app) patch for YouTube that removes background music from videos while keeping voices.

## بالعربية: طريقة التشغيل على الجوال

1. ثبّت تطبيق **ReVanced Manager** على جوالك من [revanced.app](https://revanced.app) أو من [صفحة إصداراته على GitHub](https://github.com/ReVanced/revanced-manager/releases).
2. في ReVanced Manager: افتح تبويب **Patches** ← اضغط زر القلم ✏️ ← زر **+** ← **Enter URL** ← الصق هذا الرابط:
   `https://github.com/awab3487/ME241---Works-Over-the-Semster/releases/download/music-remover-latest/patches-bundle.json`
   (بهذه الطريقة يحدّث Manager التعديل تلقائياً عند صدور نسخة جديدة.)
3. اختر **YouTube** من قائمة التطبيقات، ثم في قائمة التعديلات فعّل **Remove music** مع التعديلات الرسمية التي تريدها (تعديل GmsCore support ضروري لتسجيل الدخول). ثم اضغط Patch وثبّت التطبيق الناتج، وثبّت GmsCore (MicroG) إذا طلب منك Manager ذلك.
4. أضف زر **No music / بدون موسيقى** إلى الإعدادات السريعة: اسحب شريط الإشعارات للأسفل مرتين ← زر التعديل (القلم) ← اسحب زر «بدون موسيقى» إلى الأعلى.
   - **ضغطة** على الزر: تشغيل أو إيقاف إزالة الموسيقى فوراً أثناء المشاهدة.
   - **ضغطة مطوّلة**: اختيار قوة الإزالة (منخفضة، متوسطة، عالية) أو الإيقاف.

الميزة تعمل تلقائياً بعد التعديل بقوة متوسطة. تتم المعالجة على الجوال فقط ولا يُرفع أي شيء.

**حدود الميزة:** الإزالة تعتمد على معالجة الصوت وليس على الذكاء الاصطناعي، فتخفض الموسيقى الخلفية خلف المتحدث بقوة، أما الموسيقى الممزوجة بنفس طريقة الصوت البشري (مثل آلات الأغاني) فتُزال جزئياً فقط. يتأخر الصوت نحو 40 ملي ثانية، وهذا غير ملحوظ.

## How it works

* The **Remove music** patch replaces every call to `AudioTrack.write(ByteBuffer, int, int)`, `AudioTrack.flush()` and `AudioTrack.release()` in the app with calls to `MusicRemoverPatch` in the extension. It does not depend on obfuscated YouTube classes, so it works across YouTube versions.
* `TrackProcessor` runs the PCM audio of each track through `VoiceIsolator` before it is written. The audio keeps its length and is delayed by one FFT frame (about 43 ms at 48 kHz). Partial non-blocking writes are handled by writing the already processed bytes first.
* `VoiceIsolator` attenuates each frequency bin based on stereo position (voices are centered, music is wide), steadiness (sustained chords and pads) and frequency range.
* A resource patch adds a Quick Settings tile (`MusicRemoverTileService`) to turn the music remover on and off while watching, and a dialog (`MusicRemoverSettingsActivity`, opened by long pressing the tile) to choose the strength.

## Building

The [workflow](../.github/workflows/revanced-music-remover.yml) builds the patches file on every push that changes this directory and publishes it as the `music-remover-latest` release, together with `patches-bundle.json`, which ReVanced Manager can add with **Enter URL**.

To build locally, authenticate to GitHub Packages with a token that has the `read:packages` scope, for example in `~/.gradle/gradle.properties`:

```properties
gpr.user = <GitHub user name>
gpr.key = <token>
githubPackagesUsername = <GitHub user name>
githubPackagesPassword = <token>
```

Then run `./gradlew :extensions:musicremover:test :patches:buildAndroid`. The patches file is written to `patches/build/libs`.
