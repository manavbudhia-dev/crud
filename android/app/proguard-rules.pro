# PdfBox-Android: optional JPEG 2000 codec is not bundled.
-dontwarn com.gemalto.jp2.**
# PdfBox loads filters, fonts and encodings reflectively/by name; keep it intact.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }

# Enum names are used as navigation arguments and stored preferences.
-keepclassmembers enum com.papervault.scanner.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep line numbers for readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
