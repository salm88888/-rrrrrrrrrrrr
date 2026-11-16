package com.miyahna.water.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AppStateSnapshotEntity::class,
        PendingOfflineOpEntity::class,
        CustomerEntity::class,
        DeliveryLocationEntity::class,
        TripGpsPointEntity::class,
        TripStateTransitionEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class MiyahnaDatabase : RoomDatabase() {
    abstract fun stateDao(): MiyahnaStateDao
    abstract fun customerDao(): CustomerDao
    abstract fun deliveryLocationDao(): DeliveryLocationDao
    abstract fun tripGpsPointDao(): TripGpsPointDao
    abstract fun tripStateTransitionDao(): TripStateTransitionDao

    companion object {
        @Volatile
        private var instance: MiyahnaDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `customers` (
                        `id` TEXT NOT NULL,
                        `customerId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `phone` TEXT NOT NULL,
                        `altPhone` TEXT,
                        `district` TEXT NOT NULL,
                        `addressDetails` TEXT NOT NULL,
                        `lat` REAL NOT NULL,
                        `lng` REAL NOT NULL,
                        `tankCapacityLiters` INTEGER NOT NULL,
                        `tankRemainingLiters` INTEGER,
                        `notes` TEXT,
                        `createdAt` TEXT NOT NULL,
                        `isActive` INTEGER NOT NULL,
                        `totalOrdersCount` INTEGER NOT NULL,
                        `totalLitersDelivered` INTEGER NOT NULL,
                        `isOfflineCreated` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_customers_name` ON `customers` (`name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_customers_phone` ON `customers` (`phone`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_customers_customerId` ON `customers` (`customerId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_customers_district` ON `customers` (`district`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `delivery_locations` (
                        `id` TEXT NOT NULL,
                        `tripId` TEXT,
                        `tripNumber` TEXT,
                        `orderId` TEXT,
                        `customerId` TEXT,
                        `customerName` TEXT,
                        `truckId` TEXT NOT NULL,
                        `truckPlate` TEXT NOT NULL,
                        `driverId` TEXT NOT NULL,
                        `driverName` TEXT NOT NULL,
                        `latitude` REAL NOT NULL,
                        `longitude` REAL NOT NULL,
                        `accuracyMeters` REAL,
                        `altitudeMeters` REAL,
                        `speedKmH` REAL NOT NULL,
                        `deliveredLiters` INTEGER,
                        `captureSource` TEXT NOT NULL,
                        `notes` TEXT,
                        `capturedAtIso` TEXT NOT NULL,
                        `capturedAtEpochMs` INTEGER NOT NULL,
                        `isSynced` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_delivery_locations_tripId` ON `delivery_locations` (`tripId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_delivery_locations_customerId` ON `delivery_locations` (`customerId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_delivery_locations_orderId` ON `delivery_locations` (`orderId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_delivery_locations_capturedAtEpochMs` ON `delivery_locations` (`capturedAtEpochMs`)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `trip_gps_points` (
                        `id` TEXT NOT NULL,
                        `tripId` TEXT NOT NULL,
                        `lat` REAL NOT NULL,
                        `lng` REAL NOT NULL,
                        `accuracy` REAL,
                        `speedKmH` REAL NOT NULL,
                        `timestamp` TEXT NOT NULL,
                        `seq` INTEGER NOT NULL,
                        `distanceFromPrevKm` REAL NOT NULL,
                        `isWeakSignal` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `capturedAtEpochMs` INTEGER NOT NULL,
                        `isSynced` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_gps_points_tripId` ON `trip_gps_points` (`tripId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_gps_points_capturedAtEpochMs` ON `trip_gps_points` (`capturedAtEpochMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_gps_points_isSynced` ON `trip_gps_points` (`isSynced`)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `app_state_snapshots` ADD COLUMN `fleetTrucksJson` TEXT NOT NULL DEFAULT '[]'")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `trip_state_transitions` (
                        `id` TEXT NOT NULL,
                        `tripId` TEXT NOT NULL,
                        `tripNumber` TEXT NOT NULL,
                        `truckId` TEXT NOT NULL,
                        `driverId` TEXT NOT NULL,
                        `driverName` TEXT NOT NULL,
                        `fromState` TEXT NOT NULL,
                        `toState` TEXT NOT NULL,
                        `triggerSource` TEXT NOT NULL,
                        `actorRole` TEXT NOT NULL,
                        `actorName` TEXT NOT NULL,
                        `reason` TEXT,
                        `details` TEXT,
                        `lat` REAL,
                        `lng` REAL,
                        `speedKmH` REAL,
                        `timestamp` TEXT NOT NULL,
                        `timestampEpochMs` INTEGER NOT NULL,
                        `reviewedByManager` INTEGER NOT NULL,
                        `reviewedAt` TEXT,
                        `reviewNote` TEXT,
                        `isSyncedToCloud` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_state_transitions_tripId` ON `trip_state_transitions` (`tripId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_state_transitions_timestampEpochMs` ON `trip_state_transitions` (`timestampEpochMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_state_transitions_isSyncedToCloud` ON `trip_state_transitions` (`isSyncedToCloud`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_state_transitions_reviewedByManager` ON `trip_state_transitions` (`reviewedByManager`)")
            }
        }

        fun getInstance(context: Context): MiyahnaDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MiyahnaDatabase::class.java,
                    "miyahna_offline.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration(dropAllTables = false)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
