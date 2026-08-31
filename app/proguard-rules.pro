# Native symbols are loaded through JNI and are kept by the Android linker.
-keep class com.xiaoxin.voicetotext.android.asr.WhisperNative { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
