package com.autobots.camera.upload

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Stores [UploadStatus] as its constant name.
 *
 * Written out rather than left to Room's built-in enum handling because [UploadDao] compares
 * against string literals (`WHERE status = 'Uploading'`) inside raw SQL. Room cannot check
 * those literals against the storage format, so pinning it here is what keeps the two from
 * drifting apart silently.
 */
object UploadStatusConverter {
    @TypeConverter
    fun toDb(status: UploadStatus): String = status.name

    @TypeConverter
    fun fromDb(value: String): UploadStatus =
        runCatching { UploadStatus.valueOf(value) }.getOrDefault(UploadStatus.Failed)
}

@Database(entities = [UploadItem::class], version = 2, exportSchema = true)
@TypeConverters(UploadStatusConverter::class)
abstract class UploadDatabase : RoomDatabase() {

    abstract fun uploadDao(): UploadDao

    companion object {
        @Volatile
        private var instance: UploadDatabase? = null

        fun get(context: Context): UploadDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(appContext: Context): UploadDatabase =
            Room.databaseBuilder(appContext, UploadDatabase::class.java, DB_NAME)
                // Normally indefensible for user data — safe here, and only because of two
                // decisions made before this table existed (docs/PHASES.md §2.5, §2.3):
                // nothing ever deletes the JPEGs, so this table holds *progress*, not data;
                // and object keys are deterministic, so re-uploading overwrites instead of
                // duplicating. Losing the table therefore costs one redundant upload pass and
                // no orphaned objects. Real migrations still get written — this is the net
                // that stops a forgotten one from killing the app on launch.
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()

        /**
         * v1 → v2: room for the storage side's own object name.
         *
         * Written out even though [fallbackToDestructiveMigration] would cover it, because
         * dropping the table costs a full re-upload of everything already sent. The net is
         * there for the migration somebody forgets, not as a substitute for writing them.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE upload_queue ADD COLUMN remoteKey TEXT")
                db.execSQL("ALTER TABLE upload_queue ADD COLUMN remoteUri TEXT")
            }
        }

        private const val DB_NAME = "autobots_upload.db"
    }
}
