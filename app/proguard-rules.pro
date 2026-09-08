# Fortress ProGuard / R8 rules.
#
# The scanner surfaces are called either from JNI (fortress-native.cpp) or via
# reflection-free runtime wiring, so we pin the JNI boundary explicitly and let
# R8 shrink everything else.

# JNI entry points are resolved by symbol name — never rename.
-keepclasseswithmembernames class dev.fortress.scanner.RootkitHeuristics {
    native <methods>;
}

# Model classes serialised into scan reports.
-keepclassmembers class dev.fortress.scanner.** { <fields>; }
-keepclassmembers class dev.fortress.net.PacketEvent { <fields>; }
-keepclassmembers class dev.fortress.vt.VtReport { <fields>; }
-keepclassmembers class dev.fortress.cve.CveEntry { <fields>; }

# OkHttp / Conscrypt safety net (platform TLS is used, but keep the hints).
-dontwarn okhttp3.**
-dontwarn org.conscrypt.**
