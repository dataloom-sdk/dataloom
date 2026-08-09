# DataLoom DataStore Storage Provider — consumer R8/ProGuard rules.
# Applied to consuming applications automatically by the Android build tools.

# Preserve the public API surface of the storage provider class.
-keep class io.dataloom.storage.datastore.DataStoreStorageProvider {
    public *;
}
