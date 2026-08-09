# DataLoom Room Storage Provider — consumer R8/ProGuard rules.
# Applied to consuming applications automatically by the Android build tools.

# Preserve the public storage provider classes and database builder.
-keep class io.dataloom.storage.room.RoomStorageProvider { *; }
-keep class io.dataloom.storage.room.DataLoomStorageDatabaseBuilder { *; }

# Preserve Room entity and DAO classes from R8 optimizations.
-keep class io.dataloom.storage.room.internal.** { *; }
-keepclassmembers class io.dataloom.storage.room.internal.** { *; }
