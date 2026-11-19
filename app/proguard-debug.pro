# Debug-only R8 policy: shrink unused code and resources, but never rewrite names.
#
# -dontobfuscate keeps every class/method/field name verbatim, so logcat stack traces
# need no retrace. -dontoptimize keeps R8 from inlining methods or merging classes,
# which would drop frames and shift line numbers even with names intact; it also
# means the -assumenosideeffects rules in proguard-rules.pro do NOT strip Timber/Log
# calls here - debug keeps its logging.
-dontobfuscate
-dontoptimize

# Belt and braces: original source file names and line numbers in stack traces.
-keepattributes SourceFile,LineNumberTable