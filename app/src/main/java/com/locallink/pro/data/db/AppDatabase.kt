package com.locallink.pro.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SessionEntity::class, MessageEntity::class, ExperienceEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun experienceDao(): ExperienceDao

    companion object {
        /** v1 → v2: learned pilot routines ("experiences"). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `experiences` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `taskNorm` TEXT NOT NULL,
                        `taskRaw` TEXT NOT NULL,
                        `stepsJson` TEXT NOT NULL,
                        `successCount` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_experiences_taskNorm` ON `experiences` (`taskNorm`)",
                )
            }
        }

        /** v2 → v3: parameterized routine templates ({q}-slotted traces). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `experiences` ADD COLUMN `slotResidual` TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v3 → v4: routine library — display label + optional daily schedule. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `experiences` ADD COLUMN `label` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `experiences` ADD COLUMN `scheduleHour` INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE `experiences` ADD COLUMN `scheduleMinute` INTEGER NOT NULL DEFAULT -1")
            }
        }
    }
}
