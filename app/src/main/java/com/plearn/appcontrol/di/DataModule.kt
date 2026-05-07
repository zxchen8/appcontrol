package com.plearn.appcontrol.di

import android.content.Context
import androidx.room.Room
import com.plearn.appcontrol.data.local.AppControlDatabase
import com.plearn.appcontrol.data.local.dao.ContinuousSessionDao
import com.plearn.appcontrol.data.local.dao.CredentialProfileDao
import com.plearn.appcontrol.data.local.dao.CredentialSetDao
import com.plearn.appcontrol.data.local.dao.StepRunDao
import com.plearn.appcontrol.data.local.dao.TaskDefinitionDao
import com.plearn.appcontrol.data.local.dao.TaskRunDao
import com.plearn.appcontrol.data.local.dao.TaskScheduleStateDao
import com.plearn.appcontrol.data.repository.CredentialRepository
import com.plearn.appcontrol.data.repository.RoomCredentialRepository
import com.plearn.appcontrol.data.repository.RoomRunRecordRepository
import com.plearn.appcontrol.data.repository.RoomSessionRepository
import com.plearn.appcontrol.data.repository.RoomTaskRepository
import com.plearn.appcontrol.data.repository.RunRecordRepository
import com.plearn.appcontrol.data.repository.SessionRepository
import com.plearn.appcontrol.data.repository.TaskRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppControlDatabase =
        Room.databaseBuilder(context, AppControlDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideTaskDefinitionDao(database: AppControlDatabase): TaskDefinitionDao = database.taskDefinitionDao()

    @Provides
    fun provideTaskScheduleStateDao(database: AppControlDatabase): TaskScheduleStateDao = database.taskScheduleStateDao()

    @Provides
    fun provideCredentialProfileDao(database: AppControlDatabase): CredentialProfileDao = database.credentialProfileDao()

    @Provides
    fun provideCredentialSetDao(database: AppControlDatabase): CredentialSetDao = database.credentialSetDao()

    @Provides
    fun provideContinuousSessionDao(database: AppControlDatabase): ContinuousSessionDao = database.continuousSessionDao()

    @Provides
    fun provideTaskRunDao(database: AppControlDatabase): TaskRunDao = database.taskRunDao()

    @Provides
    fun provideStepRunDao(database: AppControlDatabase): StepRunDao = database.stepRunDao()

    @Provides
    @Singleton
    fun provideTaskRepository(
        taskDefinitionDao: TaskDefinitionDao,
        taskScheduleStateDao: TaskScheduleStateDao,
    ): TaskRepository = RoomTaskRepository(taskDefinitionDao, taskScheduleStateDao)

    @Provides
    @Singleton
    fun provideCredentialRepository(
        credentialProfileDao: CredentialProfileDao,
        credentialSetDao: CredentialSetDao,
    ): CredentialRepository = RoomCredentialRepository(credentialProfileDao, credentialSetDao)

    @Provides
    @Singleton
    fun provideSessionRepository(continuousSessionDao: ContinuousSessionDao): SessionRepository =
        RoomSessionRepository(continuousSessionDao)

    @Provides
    @Singleton
    fun provideRunRecordRepository(
        taskRunDao: TaskRunDao,
        stepRunDao: StepRunDao,
    ): RunRecordRepository = RoomRunRecordRepository(taskRunDao, stepRunDao)

    private const val DATABASE_NAME = "appcontrol.db"
}