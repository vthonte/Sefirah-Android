package sefirah.playsound

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sefirah.Feature
import sefirah.common.R
import sefirah.common.notifications.AppNotifications
import sefirah.common.notifications.NotificationCenter
import sefirah.domain.interfaces.DeviceManager
import sefirah.domain.interfaces.NetworkManager
import sefirah.domain.model.DevicePreferences
import sefirah.domain.model.PlaySound
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Rings the phone at alarm volume so it can be found from a connected desktop.
 */
@Singleton
class PlaySoundFeature @Inject constructor(
    deviceManager: DeviceManager,
    private val context: Context,
    private val networkManager: NetworkManager,
    private val notificationCenter: NotificationCenter,
) : Feature(deviceManager) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var mediaPlayer: MediaPlayer? = null
    private var previousVolume = -1
    private var currentDeviceId: String? = null
    private var autoStopJob: Job? = null

    override fun isPrefEnabled(prefs: DevicePreferences) = prefs.playSound

    override suspend fun onStop(deviceId: String) {
        if (currentDeviceId == deviceId) stop()
    }

    fun handle(deviceId: String, message: PlaySound) {
        if (message.isPlaying) {
            start(deviceId)
        } else if (currentDeviceId == deviceId || currentDeviceId == null) {
            stop()
        }
    }

    fun stop(notifyRemote: Boolean = false) {
        autoStopJob?.cancel()
        autoStopJob = null

        val deviceId = currentDeviceId
        val shouldNotify = notifyRemote && mediaPlayer != null
        currentDeviceId = null

        releasePlayer()
        dismissUi()

        if (shouldNotify && deviceId != null) {
            networkManager.sendMessage(deviceId, PlaySound(isPlaying = false))
        }
    }

    private fun start(deviceId: String) {
        if (mediaPlayer?.isPlaying == true) {
            currentDeviceId = deviceId
            scheduleAutoStop()
            return
        }

        currentDeviceId = deviceId
        if (!startPlayer()) {
            currentDeviceId = null
            Log.e(TAG, "Failed to prepare MediaPlayer")
            return
        }

        showUi(deviceId)
        networkManager.sendMessage(deviceId, PlaySound(isPlaying = true))
        scheduleAutoStop()
    }

    private fun startPlayer(): Boolean {
        releasePlayer()
        val player = MediaPlayer()
        return try {
            val ringtoneUri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: Settings.System.DEFAULT_RINGTONE_URI
            player.setDataSource(context, ringtoneUri)
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                    .build(),
            )
            player.setWakeMode(context, PowerManager.SCREEN_DIM_WAKE_LOCK)
            player.isLooping = true
            player.prepare()

            previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0,
            )
            player.start()
            mediaPlayer = player
            true
        } catch (e: Exception) {
            Log.e(TAG, "Exception preparing player", e)
            player.release()
            releasePlayer()
            false
        }
    }

    private fun releasePlayer() {
        if (previousVolume != -1) {
            runCatching {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, previousVolume, 0)
            }
            previousVolume = -1
        }
        mediaPlayer?.let { player ->
            runCatching { if (player.isPlaying) player.stop() }
            player.release()
        }
        mediaPlayer = null
    }

    private fun showUi(deviceId: String) {
        val deviceName = deviceManager.pairedDevices.value
            .firstOrNull { it.deviceId == deviceId }
            ?.deviceName
            ?: deviceId

        val activityIntent = PlaySoundActivity.createIntent(context, deviceId, deviceName)
        runCatching { context.startActivity(activityIntent) }
            .onFailure { Log.w(TAG, "Failed to start PlaySoundActivity", it) }

        val activityPendingIntent = PendingIntent.getActivity(
            context,
            deviceId.hashCode(),
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            deviceId.hashCode() + 1,
            Intent(context, PlaySoundReceiver::class.java).setAction(PlaySoundReceiver.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        notificationCenter.showNotification(
            channelId = AppNotifications.PLAY_SOUND_CHANNEL,
            notificationId = AppNotifications.PLAY_SOUND_ID,
        ) {
            setContentTitle(context.getString(R.string.play_sound_notification_title, deviceName))
            setContentIntent(stopPendingIntent)
            setFullScreenIntent(activityPendingIntent, true)
            setOngoing(true)
            setAutoCancel(true)
            setCategory(NotificationCompat.CATEGORY_ALARM)
            setPriority(NotificationCompat.PRIORITY_HIGH)
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            setSilent(false)
        }
    }

    private fun dismissUi() {
        notificationCenter.cancelNotification(AppNotifications.PLAY_SOUND_ID)
        context.sendBroadcast(
            Intent(PlaySoundActivity.ACTION_FINISH).setPackage(context.packageName),
        )
    }

    private fun scheduleAutoStop() {
        autoStopJob?.cancel()
        autoStopJob = scope.launch {
            delay(AUTO_STOP_MS.milliseconds)
            stop(notifyRemote = true)
        }
    }

    private companion object {
        const val TAG = "PlaySoundFeature"
        const val AUTO_STOP_MS = 20_000L
    }
}
