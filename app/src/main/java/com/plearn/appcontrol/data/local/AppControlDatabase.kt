package com.plearn.appcontrol.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.plearn.appcontrol.data.local.dao.ContinuousSessionDao
import com.plearn.appcontrol.data.local.dao.CredentialProfileDao
import com.plearn.appcontrol.data.local.dao.CredentialSetDao
import com.plearn.appcontrol.data.local.dao.StepRunDao
import com.plearn.appcontrol.data.local.dao.TaskDefinitionDao
import com.plearn.appcontrol.data.local.dao.TaskRunDao
import com.plearn.appcontrol.data.local.dao.TaskScheduleStateDao
import com.plearn.appcontrol.data.local.entity.ContinuousSessionEntity
import com.plearn.appcontrol.data.local.entity.CredentialProfileEntity
import com.plearn.appcontrol.data.local.entity.CredentialSecretEntity
import com.plearn.appcontrol.data.local.entity.CredentialSetEntity
import com.plearn.appcontrol.data.local.entity.CredentialSetItemEntity
import com.plearn.appcontrol.data.local.entity.StepRunEntity
import com.plearn.appcontrol.data.local.entity.TaskDefinitionEntity
import com.plearn.appcontrol.data.local.entity.TaskRunEntity
import com.plearn.appcontrol.data.local.entity.TaskScheduleStateEntity

@Database(
    entities = [
        TaskDefinitionEntity::class,
        TaskScheduleStateEntity::class,
        CredentialProfileEntity::class,
        CredentialSecretEntity::class,
        CredentialSetEntity::class,
        CredentialSetItemEntity::class,
        ContinuousSessionEntity::class,
        TaskRunEntity::class,
        StepRunEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppControlDatabase : RoomDatabase() {
    abstract fun taskDefinitionDao(): TaskDefinitionDao
    abstract fun taskScheduleStateDao(): TaskScheduleStateDao
    abstract fun credentialProfileDao(): CredentialProfileDao
    abstract fun credentialSetDao(): CredentialSetDao
    abstract fun continuousSessionDao(): ContinuousSessionDao
    abstract fun taskRunDao(): TaskRunDao
    abstract fun stepRunDao(): StepRunDao
}