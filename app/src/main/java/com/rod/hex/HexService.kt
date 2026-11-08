package com.rod.hex

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder

/** Service de premier plan : garde le micro et Hex vivants, même écran éteint. */
class HexService : Service() {
    inner class LocalBinder : Binder() { val engine: HexEngine get() = this@HexService.engine }

    lateinit var engine: HexEngine
        private set
    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)!!
        nm.createNotificationChannel(NotificationChannel("hex", "Hex", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "hex")
            .setContentTitle("Hex t'écoute")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        engine = HexEngine(this)
        engine.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        engine.release()
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) {
            try { ctx.startForegroundService(Intent(ctx, HexService::class.java)) } catch (_: Exception) {}
        }
    }
}
