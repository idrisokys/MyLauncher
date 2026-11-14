# Galerim > sihirli değnek (basic-editor): sınıflar R8 tarafından silinip/yeniden adlandırılmasın
-keep class org.fossify.editor.** { *; }
-keep class com.zomato.photofilters.** { *; }
-keep class com.canhub.cropper.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
