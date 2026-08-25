
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

-keep class com.qm.qqzygisk.ui.activity.SettingActivity { *; }
-keep class com.qm.qqzygisk.ui.activity.BaseActivity { *; }
-keep class com.qm.qqzygisk.hook.parasitic.activity.delegate.HandlerDelegate_com_qm_qqtest { *; }
-keep class com.qm.qqzygisk.hook.parasitic.activity.delegate.IActivityManagerProxy_com_qm_qqtest { *; }
