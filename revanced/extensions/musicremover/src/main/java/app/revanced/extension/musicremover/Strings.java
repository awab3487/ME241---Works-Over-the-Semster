package app.revanced.extension.musicremover;

import java.util.Locale;

/**
 * Texts of the music remover. The extension has no resources of its own, so they are kept here.
 */
final class Strings {
    private Strings() {
    }

    private static boolean isArabic() {
        return "ar".equals(Locale.getDefault().getLanguage());
    }

    static String title() {
        return isArabic() ? "بدون موسيقى" : "No music";
    }

    static String off() {
        return isArabic() ? "متوقف" : "Off";
    }

    static String strength(MusicRemovalStrength strength) {
        switch (strength) {
            case LOW:
                return isArabic() ? "إزالة منخفضة" : "Low";
            case HIGH:
                return isArabic() ? "إزالة عالية" : "High";
            default:
                return isArabic() ? "إزالة متوسطة" : "Medium";
        }
    }

    static String strengthDescription(MusicRemovalStrength strength) {
        switch (strength) {
            case LOW:
                return isArabic() ? "منخفضة (أكثر طبيعية للأصوات)" : "Low (most natural voices)";
            case HIGH:
                return isArabic() ? "عالية (أقل موسيقى، وقد تبدو الأصوات أرق)" : "High (least music, voices may sound thinner)";
            default:
                return isArabic() ? "متوسطة (موصى بها)" : "Medium (recommended)";
        }
    }
}
