# DataLoom Room Queue Provider — consumer R8/ProGuard rules.
# Applied to consuming applications automatically by the Android build tools.

# Preserve the public queue provider class and its public API surface.
-keep class io.dataloom.queue.room.RoomQueueProvider { *; }
-keep class io.dataloom.queue.room.DataLoomDatabaseBuilder { *; }

# Preserve Room entity and DAO classes from R8 optimizations.
-keep class io.dataloom.queue.room.internal.QueueEntryEntity { *; }
-keep interface io.dataloom.queue.room.internal.QueueEntryDao { *; }
-keep class io.dataloom.queue.room.internal.DataLoomRoomDatabase { *; }

# Room requires model classes accessible for reflection.
-keepclassmembers class io.dataloom.queue.room.internal.** { *; }
