# DataLoom SQLDelight Android storage driver — consumer R8/ProGuard rules.
# Applied to consuming applications automatically by the Android build tools.

# Preserve the public factory function's signature.
-keep class io.dataloom.storage.sqldelight.android.AndroidSqlDelightStorageDatabaseFactoryKt {
    public *;
}
