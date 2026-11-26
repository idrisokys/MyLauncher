# Galerim > sihirli değnek (basic-editor): sınıflar R8 tarafından silinip/yeniden adlandırılmasın
-keep class org.fossify.editor.** { *; }
-keep class com.zomato.photofilters.** { *; }
-keep class com.canhub.cropper.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
# Rust köprüsü (JNI sembolleri sınıf/metot adlarına bağlıdır)
-keep class github.idrisokys.myl.Native { *; }
-keep class com.google.mlkit.** { *; }
