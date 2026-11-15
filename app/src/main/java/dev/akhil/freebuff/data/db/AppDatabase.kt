package dev.akhil.freebuff.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        ModelRecordEntity::class,
        ProjectEntity::class,
        TaskEntity::class,
        TaskStepEntity::class,
        ResearchSessionEntity::class,
        ResearchSourceEntity::class,
        TerminalEntryEntity::class,
        BuildRecordEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun models(): ModelDao
    abstract fun projects(): ProjectDao
    abstract fun tasks(): TaskDao
    abstract fun taskSteps(): TaskStepDao
    abstract fun research(): ResearchDao
    abstract fun terminal(): TerminalDao
    abstract fun builds(): BuildDao
    abstract fun maintenance(): MaintenanceDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "freebuff.db",
            )
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
