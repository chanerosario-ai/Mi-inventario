package com.example.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Registry of explicit Room schema migrations so updates preserve
 * existing inventory, products, and movement history without destructive rebuilds.
 */
object DatabaseMigrations {
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE products ADD COLUMN preferredAreaId INTEGER NOT NULL DEFAULT 1"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sync_queue` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `idempotencyKey` TEXT NOT NULL,
                    `entityType` TEXT NOT NULL,
                    `entityKey` TEXT NOT NULL,
                    `entityId` INTEGER NOT NULL,
                    `payloadJson` TEXT NOT NULL,
                    `entityUpdatedAt` INTEGER NOT NULL,
                    `status` TEXT NOT NULL,
                    `retryCount` INTEGER NOT NULL,
                    `lastError` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    `syncedAt` INTEGER
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_queue_idempotencyKey` ON `sync_queue` (`idempotencyKey`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_sync_queue_status` ON `sync_queue` (`status`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_sync_queue_createdAt` ON `sync_queue` (`createdAt`)"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sync_ledger` (
                    `idempotencyKey` TEXT NOT NULL,
                    `entityType` TEXT NOT NULL,
                    `entityKey` TEXT NOT NULL,
                    `entityUpdatedAt` INTEGER NOT NULL,
                    `payloadJson` TEXT NOT NULL,
                    `syncedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`idempotencyKey`)
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_sync_ledger_entityKey` ON `sync_ledger` (`entityKey`)"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sync_conflicts` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `queueItemId` INTEGER NOT NULL,
                    `entityType` TEXT NOT NULL,
                    `entityKey` TEXT NOT NULL,
                    `localPayloadJson` TEXT NOT NULL,
                    `remotePayloadJson` TEXT NOT NULL,
                    `localTimestamp` INTEGER NOT NULL,
                    `remoteTimestamp` INTEGER NOT NULL,
                    `reason` TEXT NOT NULL,
                    `resolutionStatus` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `resolvedAt` INTEGER
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_sync_conflicts_resolutionStatus` ON `sync_conflicts` (`resolutionStatus`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_sync_conflicts_createdAt` ON `sync_conflicts` (`createdAt`)"
            )
        }
    }

    val ALL_MIGRATIONS: Array<Migration> = arrayOf(
        MIGRATION_1_2
    )
}
