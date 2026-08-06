package fi.merilainen.treenivalmentaja.data.alarm

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReminderSchedulerTest {
    private lateinit var context: Context
    private lateinit var scheduler: ReminderScheduler
    private lateinit var alarmManager: AlarmManager
    private lateinit var shadowAlarmManager: ShadowAlarmManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scheduler = ReminderScheduler(context)
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        shadowAlarmManager = shadowOf(alarmManager)
    }

    @Test
    fun `schedule sets inexact alarm correctly`() {
        val time = System.currentTimeMillis() + 10000
        scheduler.schedule("session1", time, 1)

        val nextAlarm = shadowAlarmManager.peekNextScheduledAlarm()
        assertNotNull(nextAlarm)
        assert(nextAlarm!!.getTriggerAtMs() == time)
    }
}
