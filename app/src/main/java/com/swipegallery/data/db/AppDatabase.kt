package com.swipegallery.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        DecisionEntity::class,
        ChargeEntity::class,
        QueueEntity::class,
        SessionEntity::class,
        SessionActionEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun reviewDao(): ReviewDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "swipe_gallery.db")
                .build()
    }
}
