package com.lfmpixel.lfm.tools

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lfmpixel.lfm.LfmApp
import com.lfmpixel.lfm.MainActivity
import com.lfmpixel.lfm.R
import com.lfmpixel.lfm.data.Reminder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.TimeZone

/** Asks for runtime permissions when a tool first needs them. Set by the visible activity. */
interface PermissionGate {
    suspend fun ensure(permissions: List<String>): Boolean
}

/**
 * Android shows one permission request at a time: a second one launched meanwhile replaces the first, whose caller
 * then waits for ever. So requests queue here, and each looks again once its turn comes (the one before may have
 * asked for the same thing).
 */
class SerialPermissionGate(
    private val granted: (List<String>) -> Boolean,
    private val launch: (List<String>, kotlinx.coroutines.CompletableDeferred<Boolean>) -> Unit,
) : PermissionGate {
    private val one = kotlinx.coroutines.sync.Mutex()

    override suspend fun ensure(permissions: List<String>): Boolean {
        if (granted(permissions)) return true
        // Waiting for a turn can be given up; once the dialog is up, the turn is held until Android answers, even if
        // the caller gives up meanwhile (Stop), so no second dialog goes over it.
        one.lock()
        try {
            if (granted(permissions)) return true
            val answer = kotlinx.coroutines.CompletableDeferred<Boolean>()
            launch(permissions, answer)
            return kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { answer.await() }
        } finally {
            one.unlock()
        }
    }
}

/**
 * The only code that starts other apps (Messages, dialer, Clock, system panels). Each start comes from the
 * owner's tap on a confirmation sheet or card, never silently (spec section 3, "links and maps never leak").
 */
class ExternalLauncher(private val context: Context) {
    var activity: android.app.Activity? = null
    /**
     * Inkling is on screen. Android blocks an app in the background from opening another one, without an error:
     * startActivity returns as if it worked. So a hand-off is only tried while this is true.
     */
    @Volatile var onScreen = false

    fun start(intent: Intent): Boolean {
        val host = activity
        if (!onScreen) return false
        return runCatching {
            if (host != null) host.startActivity(intent)
            else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    fun sms(number: String, text: String) = start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).putExtra("sms_body", text))
    fun dial(number: String) = start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))
    fun setAlarm(hour: Int, minute: Int, label: String) = start(Intent(AlarmClock.ACTION_SET_ALARM)
        .putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
        .putExtra(AlarmClock.EXTRA_MESSAGE, label).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
    fun setTimer(seconds: Int, label: String) = start(Intent(AlarmClock.ACTION_SET_TIMER)
        .putExtra(AlarmClock.EXTRA_LENGTH, seconds).putExtra(AlarmClock.EXTRA_MESSAGE, label).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
    fun panel(action: String) = start(Intent(action))
    /** A search source, in the browser: only from the owner's tap on a sheet that shows the full address. */
    fun openPage(url: String) = (url.startsWith("https://") || url.startsWith("http://")) && start(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    /** LFM Net's own settings (provider, key, proxy). */
    fun openApp(intent: Intent) = start(intent)
    /** The apps on the launcher, as label and package, for open_app. */
    fun apps(): List<Pair<String, String>> = runCatching {
        val pm = context.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { it.second != context.packageName }.distinctBy { it.second }
    }.getOrDefault(emptyList())
    fun launch(pkg: String) = context.packageManager.getLaunchIntentForPackage(pkg)?.let { start(it) } ?: false
    /** The share sheet, for an export the owner asked for. */
    fun share(subject: String, text: String) = start(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text), subject))
}

// --- Reminders: LFM's own notification at an exact time -------------------------------------------

class Reminders(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun schedule(r: Reminder) {
        val pi = pending(r.id)
        if (alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
    }

    fun cancel(id: Long) = alarms.cancel(pending(id))

    private fun pending(id: Long): PendingIntent = PendingIntent.getBroadcast(
        context, id.toInt(), Intent(context, ReminderReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val CHANNEL = "reminders"
        fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1)
        val graph = (context.applicationContext as LfmApp).graph
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val r = graph.db.reminders().get(id) ?: return@launch
                if (r.cancelled || r.fired) return@launch
                graph.db.reminders().update(r.copy(fired = true))
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                    val n = NotificationCompat.Builder(context, Reminders.CHANNEL)
                        .setSmallIcon(R.drawable.ic_stat_lfm).setContentTitle(r.text).setContentText("Reminder")
                        .setContentIntent(open).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_REMINDER).build()
                    context.getSystemService(NotificationManager::class.java).notify(id.toInt(), n)
                }
            } finally {
                done.finish()
            }
        }
    }
}

/** Alarms are lost on restart; put pending reminders back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val graph = (context.applicationContext as LfmApp).graph
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                graph.db.reminders().pending().forEach { graph.reminders.schedule(it) }
                graph.focus.reschedule()
            } finally {
                done.finish()
            }
        }
    }
}

// --- The on-phone calendar: a local calendar that never syncs --------------------------------------

data class CalendarEvent(val id: Long, val title: String, val start: Long, val end: Long, val location: String, val calendar: String)

class LocalCalendar(private val context: Context) {
    private val resolver get() = context.contentResolver

    companion object {
        // The calendar account keeps the old name, so events made before the rename stay in the same calendar.
        const val ACCOUNT = "LFM"
        const val NAME = "On this phone"
    }

    /** Finds or creates LFM's own calendar (ACCOUNT_TYPE_LOCAL: it is never synced to any account). */
    suspend fun calendarId(): Long = withContext(Dispatchers.IO) {
        resolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.ACCOUNT_NAME}=? AND ${CalendarContract.Calendars.ACCOUNT_TYPE}=?",
            arrayOf(ACCOUNT, CalendarContract.ACCOUNT_TYPE_LOCAL), null)?.use { if (it.moveToFirst()) return@withContext it.getLong(0) }
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
        val v = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, NAME)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, NAME)
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF0B0B0C.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 0)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        }
        ContentUris.parseId(resolver.insert(uri, v)!!)
    }

    /** Wipe: removes Inkling's own calendar and every event in it (only with the calendar permission). */
    suspend fun deleteOwnCalendar(): Boolean = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) return@withContext false
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
        resolver.delete(uri, "${CalendarContract.Calendars.ACCOUNT_NAME}=? AND ${CalendarContract.Calendars.ACCOUNT_TYPE}=?",
            arrayOf(ACCOUNT, CalendarContract.ACCOUNT_TYPE_LOCAL)) > 0
    }

    suspend fun add(title: String, start: Long, end: Long, location: String): Long = withContext(Dispatchers.IO) {
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId())
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        ContentUris.parseId(resolver.insert(CalendarContract.Events.CONTENT_URI, v)!!)
    }

    suspend fun delete(id: Long): Boolean = withContext(Dispatchers.IO) {
        resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) > 0
    }

    /** Events from every calendar on the phone between two times (needs READ_CALENDAR). */
    suspend fun between(from: Long, to: Long): List<CalendarEvent> = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return@withContext emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from); ContentUris.appendId(it, to)
        }.build()
        val out = mutableListOf<CalendarEvent>()
        resolver.query(uri, arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.CALENDAR_DISPLAY_NAME),
            null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) out += CalendarEvent(c.getLong(0), c.getString(1) ?: "", c.getLong(2), c.getLong(3), c.getString(4) ?: "", c.getString(5) ?: "")
        }
        out
    }
}

// --- Contacts --------------------------------------------------------------------------------------

data class Contact(val name: String, val numbers: List<String>, val birthday: String?)

class Contacts(private val context: Context) {
    fun canRead() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Every contact's name, for the personal-word check on search queries. Empty without the permission. */
    suspend fun allNames(): List<String> = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return@withContext emptyList()
        val names = mutableListOf<String>()
        context.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null)?.use { c ->
            while (c.moveToNext()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { names += it }
        }
        names
    }

    suspend fun find(query: String, limit: Int = 5): List<Contact> = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return@withContext emptyList()
        val r = context.contentResolver
        val found = linkedMapOf<Long, String>()
        r.query(Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(query)),
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null)?.use { c ->
            while (c.moveToNext() && found.size < limit) found[c.getLong(0)] = c.getString(1) ?: ""
        }
        found.map { (id, name) ->
            val numbers = mutableListOf<String>()
            r.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID}=?", arrayOf(id.toString()), null)?.use { c ->
                while (c.moveToNext()) c.getString(0)?.let { numbers += it }
            }
            var birthday: String? = null
            r.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Event.START_DATE),
                "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.CommonDataKinds.Event.TYPE}=?",
                arrayOf(id.toString(), ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString()), null)?.use { c ->
                if (c.moveToFirst()) birthday = c.getString(0)
            }
            Contact(name, numbers.distinct(), birthday)
        }
    }
}

// --- Phone settings: torch, sound mode, Do Not Disturb ---------------------------------------------

class PhoneControls(private val context: Context) {
    private val camera = context.getSystemService(CameraManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    @Volatile var torchOn = false
        private set

    init {
        runCatching {
            camera.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { torchOn = enabled }
            }, null)
        }
    }

    fun setTorch(on: Boolean): Boolean = runCatching {
        val id = camera.cameraIdList.first { camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        camera.setTorchMode(id, on)
        true
    }.getOrDefault(false)

    val ringerMode get() = audio.ringerMode
    fun hasDndAccess() = notifications.isNotificationPolicyAccessGranted

    /** RINGER_MODE_SILENT needs Do Not Disturb access on current Android. */
    fun setRingerMode(mode: Int): Boolean = runCatching { audio.ringerMode = mode; true }.getOrDefault(false)

    /** A stream's volume as a share of its range, 0..100. */
    fun volumePercent(stream: Int): Int {
        val min = audio.getStreamMinVolume(stream); val max = audio.getStreamMaxVolume(stream)
        return if (max <= min) 0 else ((audio.getStreamVolume(stream) - min) * 100 + (max - min) / 2) / (max - min)
    }

    /** Sets a stream to [percent] of its range; the nearest step Android has. Muting the ringer needs DND access. */
    fun setVolumePercent(stream: Int, percent: Int): Boolean = runCatching {
        val min = audio.getStreamMinVolume(stream); val max = audio.getStreamMaxVolume(stream)
        audio.setStreamVolume(stream, min + ((max - min) * percent.coerceIn(0, 100) + 50) / 100, 0)
        true
    }.getOrDefault(false)

    val dndOn get() = notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    fun setDnd(on: Boolean): Boolean = runCatching {
        notifications.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        true
    }.getOrDefault(false)

    fun dndSettingsAction() = Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS

    /** Play, pause, next, previous for whatever app plays media, as a headset button would. */
    fun mediaKey(code: Int): Boolean = runCatching {
        audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
        true
    }.getOrDefault(false)
    val musicActive get() = audio.isMusicActive

    /** Battery percent and whether it is charging; no permission needed. */
    fun battery(): Pair<Int, Boolean> {
        val bm = context.getSystemService(android.os.BatteryManager::class.java)
        return (bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1) to (bm?.isCharging ?: false)
    }
}
