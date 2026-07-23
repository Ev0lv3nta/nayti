-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault

# ONNX Runtime's JNI bridge resolves these Java types and members by binary name.
# The upstream AAR does not currently ship consumer rules, so R8 must preserve
# this boundary explicitly in every minified Nayti build.
-keep class ai.onnxruntime.** { *; }
