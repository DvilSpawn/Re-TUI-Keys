# Keep the IME service entry point visible to Android framework reflection.
-keep class com.dvil.retui.keyboard.RetuiKeyboardService { *; }

# sherpa-onnx reads its config objects' fields from native code by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
