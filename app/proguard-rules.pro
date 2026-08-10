
-dontpreverify
-keepattributes SourceFile,LineNumberTable

#-obfuscationdictionary obf-dict.txt
#-classobfuscationdictionary obf-dict.txt
#-packageobfuscationdictionary obf-dict.txt

-allowaccessmodification
-overloadaggressively
-repackageclasses 'com.qm.qqzygisk.obf'

-dontwarn java.lang.reflect.AnnotatedType
-keepattributes *Annotation*

# For enumeration classes, see http://proguard.sourceforge.net/manual/examples.html#enumerations
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keep,allowobfuscation,allowoptimization class com.qm.qqzygisk.hook.core.LoadedApkHook { *; }
