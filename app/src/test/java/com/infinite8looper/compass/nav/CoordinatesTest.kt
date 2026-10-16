package com.infinite8looper.compass.nav

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class CoordinatesTest {
    @Test fun bothHemispheresOfEachAxisHaveALetterAndNoSign() {
        assertEquals("43.7022° N", formatLatitude(43.7022))
        assertEquals("72.2896° W", formatLongitude(-72.2896))
        assertEquals("33.8688° S", formatLatitude(-33.8688))
        assertEquals("151.2093° E", formatLongitude(151.2093))
        for (text in listOf(formatLatitude(-33.8688), formatLongitude(-72.2896))) assertFalse(text, text!!.contains('-'))
    }

    @Test fun zeroIsNorthAndEast() {
        assertEquals("0.0000° N", formatLatitude(0.0))
        assertEquals("0.0000° E", formatLongitude(0.0))
        assertEquals("0.0000° N", formatLatitude(-0.0))
        assertEquals("0.0000° E", formatLongitude(-0.0))
    }

    /** A value that rounds to zero has zero's letter: there is no `0.0000° S` and no `0.0000° W`. */
    @Test fun aValueThatRoundsToZeroIsNorthAndEast() {
        assertEquals("0.0000° N", formatLatitude(-0.00004))
        assertEquals("0.0000° E", formatLongitude(-0.00004))
        assertEquals("0.0001° S", formatLatitude(-0.00005))
        assertEquals("0.0001° W", formatLongitude(-0.00005))
    }

    /** Four decimal places, the fifth rounded half up on the number as it is written in decimal. */
    @Test fun theFourthPlaceIsRoundedHalfUpOnTheDecimalNumber() {
        assertEquals("43.7023° N", formatLatitude(43.70225))
        assertEquals("43.7022° N", formatLatitude(43.70224))
        assertEquals("43.7022° N", formatLatitude(43.702249999))
        assertEquals("43.7023° S", formatLatitude(-43.70225))
        assertEquals("72.2897° W", formatLongitude(-72.28965))
        assertEquals("0.1235° E", formatLongitude(0.12345))
        // One step of the last place is about 11 m of latitude.
        assertEquals(11.1, distanceMeters(GeoPoint(43.7022, -72.2896), GeoPoint(43.7023, -72.2896)), 0.1)
    }

    @Test fun valuesThatRoundUpToAWholeDegree() {
        assertEquals("44.0000° N", formatLatitude(43.99995))
        assertEquals("44.0000° N", formatLatitude(43.99999))
        assertEquals("43.9999° N", formatLatitude(43.99994))
        assertEquals("73.0000° W", formatLongitude(-72.99996))
        assertEquals("100.0000° E", formatLongitude(99.99995))
        assertEquals("1.0000° S", formatLatitude(-0.99999))
    }

    @Test fun thePoles() {
        assertEquals("90.0000° N", formatLatitude(90.0))
        assertEquals("90.0000° S", formatLatitude(-90.0))
        assertEquals("90.0000° N", formatLatitude(89.99996))
        // A latitude beyond a pole is the pole.
        assertEquals("90.0000° N", formatLatitude(90.5))
        assertEquals("90.0000° S", formatLatitude(-123.0))
    }

    /** The antimeridian is one line, and it is written `180.0000° E` from whichever side it is reached. */
    @Test fun theAntimeridianIsEast() {
        assertEquals("180.0000° E", formatLongitude(180.0))
        assertEquals("180.0000° E", formatLongitude(-180.0))
        assertEquals("180.0000° E", formatLongitude(179.99996))
        assertEquals("180.0000° E", formatLongitude(-179.99996))
        assertEquals("179.9999° E", formatLongitude(179.99994))
        assertEquals("179.9999° W", formatLongitude(-179.99994))
    }

    @Test fun aLongitudeBeyondTheAntimeridianIsBroughtRound() {
        assertEquals("170.0000° W", formatLongitude(190.0))
        assertEquals("170.0000° E", formatLongitude(-190.0))
        assertEquals("0.0000° E", formatLongitude(360.0))
        assertEquals("72.2896° W", formatLongitude(287.7104))
    }

    @Test fun whatIsNotANumberHasNoText() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(formatLatitude(bad)); assertNull(formatLongitude(bad))
        }
    }

    /** The output is the same whatever the device's language: a locale that writes a decimal comma changes nothing. */
    @Test fun aCommaDecimalLocaleDoesNotChangeTheOutput() {
        val before = Locale.getDefault()
        try {
            for (locale in listOf(Locale.GERMANY, Locale.FRANCE, Locale.forLanguageTag("ar-EG"), Locale.forLanguageTag("hi-IN-u-nu-deva"))) {
                Locale.setDefault(locale)
                // The locale really writes numbers another way.
                assertNotEquals("43.7022", String.format("%.4f", 43.7022))
                assertEquals("43.7022° N", formatLatitude(43.7022))
                assertEquals("72.2896° W", formatLongitude(-72.2896))
            }
        } finally { Locale.setDefault(before) }
    }

    // When the coordinates are a reason for location to run.

    @Test fun theCoordinatesWantLocationOnlyWhileTheSettingIsOnAndTheCompassIsOnScreen() {
        assertTrue(coordinatesWantLocation(show = true, compassOnScreen = true))
        assertFalse(coordinatesWantLocation(show = true, compassOnScreen = false))
        assertFalse(coordinatesWantLocation(show = false, compassOnScreen = true))
        assertFalse(coordinatesWantLocation(show = false, compassOnScreen = false))
    }
}
