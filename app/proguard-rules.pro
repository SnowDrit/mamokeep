# ONNX Runtime calls its Java classes from JNI; its official Android integration
# requires this rule in minimized releases.
-keep class ai.onnxruntime.** { *; }
