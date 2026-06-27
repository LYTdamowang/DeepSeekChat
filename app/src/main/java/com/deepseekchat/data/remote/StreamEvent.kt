package com.deepseekchat.data.remote

sealed class StreamEvent {
    data class Reasoning(val content: String) : StreamEvent()
    data class Content(val token: String) : StreamEvent()
}
