package com.raghavsatyadev.grammarfix.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.raghavsatyadev.grammarfix.R
import com.raghavsatyadev.grammarfix.engine.DownloadStatus
import com.raghavsatyadev.grammarfix.engine.ModelDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class ModelDownloadForegroundService : Service() {

  private val modelDownloader: ModelDownloader by inject()
  private val scope = CoroutineScope(Dispatchers.Main)
  private var observerJob: Job? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val action = intent?.action
    val url = intent?.getStringExtra(EXTRA_URL) ?: modelDownloader.defaultModelUrl

    when (action) {
      ACTION_START -> {
        startForeground(
          NOTIFICATION_ID,
          buildNotification(0, getString(R.string.notification_model_download_starting)),
        )
        scope.launch { modelDownloader.startDownload(url) }
        observeProgress()
      }

      ACTION_PAUSE -> {
        modelDownloader.pauseDownload()
      }

      ACTION_STOP -> {
        modelDownloader.cancelDownload()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
      }
    }

    return START_NOT_STICKY
  }

  private fun observeProgress() {
    observerJob?.cancel()
    observerJob = scope.launch {
      modelDownloader.progressState.collect { progress ->
        when (progress.status) {
          DownloadStatus.DOWNLOADING -> {
            val pct = progress.progressPercentage.toInt()
            val speed = progress.speedKbps / 1024f
            val text = getString(R.string.notification_model_download_progress, pct, speed)
            val notification = buildNotification(pct, text)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
          }

          DownloadStatus.COMPLETED -> {
            val notification =
              buildNotification(
                100,
                getString(R.string.notification_model_download_completed),
              )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
          }

          DownloadStatus.ERROR -> {
            val errorDetail =
              progress.errorMessage ?: getString(R.string.notification_unknown_error)
            val notification =
              buildNotification(
                0,
                getString(R.string.notification_model_download_error, errorDetail),
              )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
          }

          else -> {}
        }
      }
    }
  }

  private fun buildNotification(progress: Int, title: String): Notification {
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle(getString(R.string.notification_model_download_title))
      .setContentText(title)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setProgress(100, progress, false)
      .setOngoing(true)
      .build()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
        NotificationChannel(
          CHANNEL_ID,
          getString(R.string.channel_model_download_name),
          NotificationManager.IMPORTANCE_LOW,
        )
      val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      manager.createNotificationChannel(channel)
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    observerJob?.cancel()
    super.onDestroy()
  }

  companion object {
    const val CHANNEL_ID = "model_download_channel"
    const val NOTIFICATION_ID = 1001
    const val ACTION_START = "ACTION_START_MODEL_DOWNLOAD"
    const val ACTION_PAUSE = "ACTION_PAUSE_MODEL_DOWNLOAD"
    const val ACTION_STOP = "ACTION_STOP_MODEL_DOWNLOAD"
    const val EXTRA_URL = "EXTRA_MODEL_URL"

    fun startDownload(context: Context, url: String) {
      val intent =
        Intent(context, ModelDownloadForegroundService::class.java).apply {
          action = ACTION_START
          putExtra(EXTRA_URL, url)
        }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }
  }
}
