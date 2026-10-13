package com.infinite8looper.compass

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.infinite8looper.compass.ui.startedIfPresent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The guard round the launch of the system's text input ([startedIfPresent]), against the system's own refusal. */
@RunWith(AndroidJUnit4::class)
class TextInputLaunchTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    // An action no activity on the device answers.
    private val unanswered = Intent("com.infinite8looper.compass.test.NO_ACTIVITY_ANSWERS_THIS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @Test fun aLaunchNoActivityAnswersIsReportedAndThrowsNothing() {
        // The system refuses the launch by throwing.
        assertThrows(ActivityNotFoundException::class.java) { context.startActivity(unanswered) }
        assertFalse(startedIfPresent { context.startActivity(unanswered) })
    }

    @Test fun aLaunchThatStartsIsReportedAndAnyOtherFailureIsNotHidden() {
        assertTrue(startedIfPresent { })
        assertThrows(IllegalStateException::class.java) { startedIfPresent { throw IllegalStateException("not a missing activity") } }
    }
}
