package com.deepseekchat.di

import android.content.Context
import androidx.room.Room
import com.deepseekchat.data.local.AppDatabase
import com.deepseekchat.data.local.dao.ConversationDao
import com.deepseekchat.data.local.dao.MessageDao
import com.deepseekchat.util.ReasoningStorage
import com.deepseekchat.util.SettingsManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "deepseek_chat_tree.db"
        )
            .setTransactionExecutor(java.util.concurrent.Executors.newSingleThreadExecutor())
            .setQueryExecutor(java.util.concurrent.Executors.newSingleThreadExecutor())
            .build()
    }

    @Provides
    fun provideConversationDao(db: AppDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()

    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient {
        return HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
        }
    }

    @Provides
    @Singleton
    fun provideSettingsManager(@ApplicationContext context: Context): SettingsManager {
        return SettingsManager(context)
    }

    @Provides
    @Singleton
    fun provideReasoningStorage(@ApplicationContext context: Context): ReasoningStorage {
        return ReasoningStorage(context)
    }
}
