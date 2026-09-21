package com.nabz.news
import android.app.*;import android.content.*;import androidx.core.app.NotificationCompat
class ReminderReceiver:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent?){
 val nm=c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
 nm.createNotificationChannel(NotificationChannel("reminder","یادآوری روزانه",NotificationManager.IMPORTANCE_HIGH))
 nm.notify(999,NotificationCompat.Builder(c,"reminder").setSmallIcon(R.drawable.ic_stat_roozaneh).setContentTitle("روزانه").setContentText("وقت مرور اخبار مهم ایران و خاورمیانه است.").setAutoCancel(true).build())
}}
