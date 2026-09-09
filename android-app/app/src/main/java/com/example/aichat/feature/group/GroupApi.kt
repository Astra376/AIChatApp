package com.example.aichat.feature.group

import com.example.aichat.core.network.CreateGroupRequestDto
import com.example.aichat.core.network.GroupDetailDto
import com.example.aichat.core.network.GroupPageDto
import com.example.aichat.core.network.GroupPresenceRequestDto
import com.example.aichat.core.network.GroupStopRequestDto
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface GroupApi {
    @DELETE("v1/groups/{groupId}")
    suspend fun delete(@Path("groupId") groupId: String)

    @GET("v1/groups")
    suspend fun list(@Query("cursor") cursor: String? = null): GroupPageDto

    @POST("v1/groups")
    suspend fun create(@Body body: CreateGroupRequestDto): GroupDetailDto

    @GET("v1/groups/{groupId}")
    suspend fun detail(
        @Path("groupId") groupId: String,
        @Query("beforePosition") beforePosition: Int? = null
    ): GroupDetailDto

    @POST("v1/groups/{groupId}/stop")
    suspend fun stop(@Path("groupId") groupId: String, @Body body: GroupStopRequestDto)

    @POST("v1/groups/{groupId}/presence")
    suspend fun presence(@Path("groupId") groupId: String, @Body body: GroupPresenceRequestDto)
}

@Module
@InstallIn(SingletonComponent::class)
object GroupNetworkModule {
    @Provides
    @Singleton
    fun api(retrofit: Retrofit, client: OkHttpClient): GroupApi = retrofit.newBuilder()
        .client(client.newBuilder()
            .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build())
        .build().create(GroupApi::class.java)

    @Provides
    @Singleton
    fun stream(client: OkHttpClient, json: Json): GroupStreamingClient = WorkerGroupStreamingClient(client, json)
}
