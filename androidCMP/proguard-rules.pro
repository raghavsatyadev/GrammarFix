# Release R8 rules. Libraries ship their own consumer rules; these cover what they cannot see.

# llama.cpp's JNI code binds to these classes and their native methods by name.
-keep class com.raghavsatyadev.grammarfix.llama.** { *; }
