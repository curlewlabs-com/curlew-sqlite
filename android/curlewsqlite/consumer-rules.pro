# Applied to every app that depends on this library, so a consumer writes no rule.
#
# The JNI shim's JNI_OnLoad resolves this class by name with FindClass and then
# RegisterNatives against it, so R8 renaming or stripping it breaks the lookup.
# The shim answers that with FatalError at System.loadLibrary time, a loud
# abort a release build shows at once; without this rule, every minified build
# would take it. Members are kept with the class because RegisterNatives
# matches each native method by name and signature.
-keep class com.curlewlabs.sqlite.CurlewSQLite { *; }
