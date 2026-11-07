package com.example.update

import java.io.File

data class AppUpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val url: String,
    val sha256: String = "",
    val sizeBytes: Long = 0L,
    val minVersionCode: Long = 0L,
    val mandatory: Boolean = false,
    val notes: String = "",
    val publishedAt: String = ""
)

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class UpToDate(val versionName: String) : UpdateState()
    data class Available(val info: AppUpdateInfo) : UpdateState()
    data class Downloading(val info: AppUpdateInfo, val progress: Int) : UpdateState()
    data class ReadyToInstall(val info: AppUpdateInfo, val file: File) : UpdateState()
    data class Failed(val message: String) : UpdateState()
}
