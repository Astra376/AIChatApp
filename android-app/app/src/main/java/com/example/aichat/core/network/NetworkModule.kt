package com.example.aichat.core.network

import com.example.aichat.BuildConfig
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        sessionRefreshingInterceptor: SessionRefreshingInterceptor
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(sessionRefreshingInterceptor)
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                }
            )
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(json: Json, okHttpClient: OkHttpClient): Retrofit {
        val baseUrl = if (BuildConfig.API_BASE_URL.endsWith("/")) {
            BuildConfig.API_BASE_URL
        } else {
            "${BuildConfig.API_BASE_URL}/"
        }
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Provides
    fun provideAuthApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)

    @Provides
    fun provideProfileApi(retrofit: Retrofit): ProfileApi = retrofit.create(ProfileApi::class.java)

    @Provides
    fun provideCharacterApi(retrofit: Retrofit): CharacterApi = retrofit.create(CharacterApi::class.java)

    @Provides
    fun provideHomeApi(retrofit: Retrofit): HomeApi = retrofit.create(HomeApi::class.java)

    @Provides
    fun provideConversationApi(retrofit: Retrofit): ConversationApi = retrofit.create(ConversationApi::class.java)

    @Provides
    fun provideChatApi(retrofit: Retrofit): ChatApi = retrofit.create(ChatApi::class.java)

    @Provides
    @Singleton
    fun provideChatStreamingClient(
        okHttpClient: OkHttpClient,
        json: Json
    ): ChatStreamingClient = WorkerStreamingClient(okHttpClient, json)

    @Provides
    fun provideImageApi(retrofit: Retrofit, okHttpClient: OkHttpClient): ImageApi = retrofit.newBuilder()
        .client(okHttpClient.newBuilder()
            .readTimeout(170, TimeUnit.SECONDS)
            .callTimeout(175, TimeUnit.SECONDS)
            .build())
        .build().create(ImageApi::class.java)
}
