package com.infinite8looper.compass.nav

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class PrivacyLinkTest {
    @Test fun theLinkIsTheCompassSectionOfThePublishedPolicy() {
        val uri = URI(PRIVACY_POLICY_URL)
        assertEquals("https", uri.scheme)
        assertEquals("infinite8looper.github.io", uri.host)
        assertEquals("/privacy.html", uri.path)
        assertEquals("compass", uri.fragment)
    }

    @Test fun theAddressShownOnTheWatchIsTheLinkWithoutItsSchemeAndSection() {
        assertEquals("infinite8looper.github.io/privacy.html", PRIVACY_POLICY_ADDRESS)
        assertEquals(PRIVACY_POLICY_URL, "https://$PRIVACY_POLICY_ADDRESS#compass")
    }

    @Test fun beforeATapTheRowSaysNothingMoreAndTakesATap() {
        val row = privacyRow(PhoneOpen.NOT_SENT)
        assertEquals("", row.detail)
        assertTrue(row.takesTap)
    }

    @Test fun whileThePhoneIsAskedTheRowTakesNoSecondTap() {
        val row = privacyRow(PhoneOpen.SENDING)
        assertEquals("", row.detail)
        assertFalse(row.takesTap)
    }

    @Test fun aPhoneThatTookTheLinkIsSaidSo() {
        assertEquals(PhoneOpen.OPENED, phoneOpenAfter(delivered = true))
        val row = privacyRow(PhoneOpen.OPENED)
        assertEquals("Opened on your phone", row.detail)
        assertTrue(row.takesTap)
    }

    @Test fun noPhoneOrAFailedCallOrNoAnswerInTimeShowsTheAddressOnTheWatch() {
        assertEquals(PhoneOpen.NOT_OPENED, phoneOpenAfter(delivered = false))
        assertEquals(PhoneOpen.NOT_OPENED, phoneOpenAfter(delivered = null))
        val row = privacyRow(PhoneOpen.NOT_OPENED)
        // Two lines, broken before the path: the address is one word too long for one line of a row.
        assertEquals("infinite8looper.github.io\n/privacy.html", row.detail)
        assertEquals(PRIVACY_POLICY_ADDRESS, row.detail.replace("\n", ""))
        assertTrue(row.takesTap)
    }

    @Test fun theWatchWaitsForThePhoneForAFewSecondsAtMost() {
        assertTrue(PHONE_OPEN_WAIT_MS in 1_000L..15_000L)
    }

    @Test fun theRowsWords() {
        assertEquals("Privacy policy", PRIVACY_POLICY_LABEL)
        assertEquals("Open on your phone", PRIVACY_POLICY_CLICK_LABEL)
    }
}
