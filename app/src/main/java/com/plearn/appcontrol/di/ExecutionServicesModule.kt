package com.plearn.appcontrol.di

import com.plearn.appcontrol.appservice.ManualTaskExecutionService
import com.plearn.appcontrol.appservice.SchedulerRecoveryOrchestrator
import com.plearn.appcontrol.data.repository.CredentialRepository
import com.plearn.appcontrol.data.repository.RunRecordRepository
import com.plearn.appcontrol.data.repository.SessionRepository
import com.plearn.appcontrol.data.repository.TaskRepository
import com.plearn.appcontrol.dsl.TaskDslParser
import com.plearn.appcontrol.runner.RepositoryBackedTaskExecutionRecorder
import com.plearn.appcontrol.runner.RunnerTimeSource
import com.plearn.appcontrol.runner.TaskExecutionRecorder
import com.plearn.appcontrol.runner.TaskRunner
import com.plearn.appcontrol.scheduler.CronScheduleCalculator
import com.plearn.appcontrol.scheduler.SchedulerTimeSource
import com.plearn.appcontrol.scheduler.SystemSchedulerTimeSource
import com.plearn.appcontrol.scheduler.TaskSchedulerService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.UUID
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ExecutionServicesModule {
    @Provides
    @Singleton
    fun provideTaskExecutionRecorder(runRecordRepository: RunRecordRepository): TaskExecutionRecorder =
        RepositoryBackedTaskExecutionRecorder(runRecordRepository)

    @Provides
    @Singleton
    fun provideManualTaskExecutionService(
        parser: TaskDslParser,
        taskRunner: TaskRunner,
        executionRecorder: TaskExecutionRecorder,
        timeSource: RunnerTimeSource,
    ): ManualTaskExecutionService = ManualTaskExecutionService(
        parser = parser,
        taskRunner = taskRunner,
        executionRecorder = executionRecorder,
        timeSource = timeSource,
        runIdFactory = { UUID.randomUUID().toString() },
    )

    @Provides
    @Singleton
    fun provideSchedulerTimeSource(): SchedulerTimeSource = SystemSchedulerTimeSource

    @Provides
    @Singleton
    fun provideCronScheduleCalculator(): CronScheduleCalculator = CronScheduleCalculator()

    @Provides
    @Singleton
    fun provideTaskSchedulerService(
        parser: TaskDslParser,
        taskRepository: TaskRepository,
        credentialRepository: CredentialRepository,
        sessionRepository: SessionRepository,
        taskRunner: TaskRunner,
        executionRecorder: TaskExecutionRecorder,
        schedulerTimeSource: SchedulerTimeSource,
        cronScheduleCalculator: CronScheduleCalculator,
    ): TaskSchedulerService = TaskSchedulerService(
        parser = parser,
        taskRepository = taskRepository,
        credentialRepository = credentialRepository,
        sessionRepository = sessionRepository,
        taskRunner = taskRunner,
        executionRecorder = executionRecorder,
        timeSource = schedulerTimeSource,
        cronScheduleCalculator = cronScheduleCalculator,
        sessionIdFactory = { UUID.randomUUID().toString() },
    )

    @Provides
    @Singleton
    fun provideSchedulerRecoveryOrchestrator(
        taskSchedulerService: TaskSchedulerService,
    ): SchedulerRecoveryOrchestrator = SchedulerRecoveryOrchestrator(
        recoverDispatcher = taskSchedulerService::dispatchDueTasks,
    )
}