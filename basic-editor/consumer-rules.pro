# NativeImageProcessor (androidphotofilters) uses JNI
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.zomato.photofilters.** { *; }
