package com.locallink.pro.di

import android.content.Context
import androidx.room.Room
import com.locallink.pro.data.db.AppDatabase
import com.locallink.pro.data.db.ExperienceDao
import com.locallink.pro.data.db.MessageDao
import com.locallink.pro.data.db.SessionDao
import com.locallink.pro.data.local.SettingsPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSettingsPreferences(
        @ApplicationContext context: Context
    ): SettingsPreferences = SettingsPreferences(context)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "omnipin.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .build()

    @Provides fun provideSessionDao(db: AppDatabase): SessionDao = db.sessionDao()
    @Provides fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()
    @Provides fun provideExperienceDao(db: AppDatabase): ExperienceDao = db.experienceDao()
}
