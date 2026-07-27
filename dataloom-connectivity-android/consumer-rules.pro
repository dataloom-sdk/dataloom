# DataLoom Android Connectivity Provider — consumer R8/ProGuard rules.
# Applied to consuming applications automatically by the Android build tools.

# Preserve the public provider class and its public API surface.
-keep class io.dataloom.connectivity.android.AndroidConnectivityProvider { *; }
