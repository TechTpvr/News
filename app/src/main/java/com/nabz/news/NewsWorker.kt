package com.nabz.news.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nabz.news.R
import com.nabz.news.data.NewsRepository

class NewsWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        val news = NewsRepository.fetch(applicationContext)
        val prefs = applicationContext.getSharedPreferences("nabz", Context.MODE_PRIVATE)
        val seen = prefs.getStringSet("seen_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("news", "اخبار مهم", NotificationManager.IMPORTANCE_HIGH))

        // Only genuinely new high-priority stories trigger notifications.
        news.filter { it.importance >= 55 && !seen.contains(it.id) }
            .sortedByDescending { it.importance }
            .take(3)
            .forEachIndexed { index, item ->
                val pending = PendingIntent.getActivity(applicationContext, item.id.hashCode(), Intent(applicationContext, com.nabz.news.MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("news_id", item.id)
                }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val label = when {
                    item.importance >= 80 -> "🔴 فوری"
                    item.importance >= 65 -> "🟠 مهم"
                    else -> "🔵 تازه"
                }
                nm.notify(item.id.hashCode(), NotificationCompat.Builder(applicationContext, "news")
                    .setSmallIcon(R.drawable.ic_stat_roozaneh)
                    .setContentIntent(pending)
                    .setContentTitle("روزانه • $label • ${item.source}")
                    .setContentText(item.title)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(item.title + if (item.summary.isNotBlank()) "\n${item.summary.take(180)}" else ""))
                    .setPriority(if (item.importance >= 65) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .build())
            }
        seen.addAll(news.map { it.id })
        prefs.edit().putStringSet("seen_ids", seen.takeLast(300).toSet()).apply()
        Result.success()
    }.getOrElse { Result.retry() }
}
