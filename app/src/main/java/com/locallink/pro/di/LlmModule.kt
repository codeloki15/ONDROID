package com.locallink.pro.di

import com.locallink.pro.service.llm.LlmEngine
import com.locallink.pro.service.llm.LlmService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LlmModule {
    @Binds @Singleton
    abstract fun bindLlmEngine(impl: LlmService): LlmEngine
}
