package com.plearn.appcontrol.di

import com.plearn.appcontrol.data.local.AppControlDatabase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppControlDatabaseEntryPoint {
    fun database(): AppControlDatabase
}