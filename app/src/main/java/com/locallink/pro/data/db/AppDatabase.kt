package com.locallink.pro.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SessionEntity::class, MessageEntity::class, ExperienceEntity::class, MemoryFactEntity::class, NotificationRuleEntity::class],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun experienceDao(): ExperienceDao
    abstract fun memoryFactDao(): MemoryFactDao
    abstract fun notificationRuleDao(): NotificationRuleDao

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

        /** v5 → v6: notification trigger rules. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `notification_rules` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `appPackage` TEXT NOT NULL,
                        `matchText` TEXT NOT NULL,
                        `action` TEXT NOT NULL,
                        `agentTask` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        /** v4 → v5: persistent user memory facts. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_facts` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `key` TEXT NOT NULL,
                        `value` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_facts_key` ON `memory_facts` (`key`)",
                )
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
