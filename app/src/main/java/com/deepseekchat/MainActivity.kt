package com.deepseekchat

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.deepseekchat.data.repository.SessionRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var sessionRepository: SessionRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        runCatching {
            runBlocking(Dispatchers.IO) {
                sessionRepository.cleanupStorageGarbage()
            }
        }.onFailure {
            Log.e("DeepSeekCleanup", "Storage cleanup failed", it)
        }
        setContent {
            DeepSeekChatApp()
        }
    }
}
