# DataLoom Android platform artifact — consumer R8/ProGuard rules.
# Applied to consuming applications automatically by the Android build tools.

# Preserve the public wiring API surface.
-keep class io.dataloom.android.AndroidDataLoomProviders { *; }
-keep class io.dataloom.android.AndroidDataLoomProvidersKt { *; }
