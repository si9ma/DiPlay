package com.shilapi.xcertplay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HeadUnitMapDestinationTest {
    private val send = HeadUnitMapDestination.ACTION_FROM_MAP
    private val recv = HeadUnitMapDestination.ACTION_TO_MAP

    @After fun reset() {
        HeadUnitMapDestination.onEvent = null
        HeadUnitMapDestination.resetForTest()
    }

    private fun planned(at: String, id: String, latitude: Double?, longitude: Double?) {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val method = HeadUnitMapDestination::class.java.getDeclaredMethod("onRoutePlanned",
            android.content.Context::class.java, com.shilapi.xcertplay.hud.BydMapDestinationLog.Entry::class.java,
            Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val entry = com.shilapi.xcertplay.hud.BydMapDestinationLog.Entry(at,
            com.shilapi.xcertplay.hud.BydMapDestinationLog.Place(id, "Gate", latitude, longitude), emptyList())
        method.invoke(HeadUnitMapDestination, app, entry, false)
    }

    /**
     * Verbatim timing from a Di150VCP: the name and POI id at 18:59:51.350, the same place with its
     * point at 18:59:52.384. The id keys both, so the point was "not a new route" and the hold ran its
     * full five seconds before sending; it now goes as soon as the point is in.
     */
    @Test fun theCoordinateAHeldRouteWaitsForReleasesItAtOnce() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        AirPlayPersistence.saveMapDestinationFollow(app, true)
        val events = mutableListOf<String>()
        HeadUnitMapDestination.onEvent = { events += it }
        planned("10-12 18:59:51.350", "B0TESTPOI1", null, null)
        assertTrue(events.toString(), events.any { it.startsWith("hand-over held") })
        planned("10-12 18:59:52.384", "B0TESTPOI1", 30.1, 110.9)
        assertTrue(events.toString(), events.any { it.contains("waiting for arrived; handing it over now") })
        assertTrue(events.toString(), events.any { it.startsWith("handed the held route, with its coordinate") })
        assertEquals(30.1, HeadUnitMapDestination.latest!!.latitude!!, 1e-9)
    }

    @Test fun routePlanningRequestCarriesTheDestination() {
        val destination = HeadUnitMapDestination.destination(recv, mapOf(
            "KEY_TYPE" to 10032,
            "EXTRA_SLAT" to 22.5, "EXTRA_SLON" to 113.9,
            "EXTRA_DLAT" to 34.56789, "EXTRA_DLON" to 114.56789, "EXTRA_DNAME" to "Airport",
        ))!!
        assertEquals(34.56789, destination.latitude!!, 1e-9)
        assertEquals(114.56789, destination.longitude!!, 1e-9)
        assertEquals("Airport", destination.name)
        assertEquals("RECV EXTRA_DLAT/EXTRA_DLON", destination.source)
    }

    @Test fun directNavigationUsesPlainCoordinates() {
        val destination = HeadUnitMapDestination.destination(recv, mapOf(
            "KEY_TYPE" to 10038, "LAT" to "33.5", "LON" to "113.5", "POINAME" to "Station", "DEV" to 0,
        ))!!
        assertEquals(33.5, destination.latitude!!, 1e-9)
        assertEquals("Station", destination.name)
    }

    @Test fun plainCoordinatesFromTheMapAreNotADestination() {
        assertNull(HeadUnitMapDestination.destination(send, mapOf("KEY_TYPE" to 10038, "LAT" to 31.0, "LON" to 121.0)))
    }

    @Test fun toPrefixedCoordinatesAreADestination() {
        val destination = HeadUnitMapDestination.destination(send, mapOf(
            "KEY_TYPE" to 10002, "FROM_LAT" to 22.5, "FROM_LON" to 113.9, "TO_LAT" to 22.6, "TO_LON" to 114.1,
        ))!!
        assertEquals(22.6, destination.latitude!!, 1e-9)
        assertFalse(destination.region)
    }

    /** The BYD DiLink 5 trip broadcast seen on a Di150VCP: district names, no coordinate. */
    @Test fun tripBroadcastGivesTheRegionOnly() {
        val destination = HeadUnitMapDestination.destination(send, mapOf(
            "KEY_TYPE" to 10002, "EXTRA_STATE" to 1, "IS_BYD_MAP" to true, "TRIP_REMAIN_TIME" to 1800,
            "FROM_AD_CODE" to 100001, "FROM_AD_NAME" to "示例市", "FROM_CITY_NAME" to "示例市", "FROM_TOWN_NAME" to "东区",
            "TO_AD_CODE" to 100002, "TO_AD_NAME" to "示例市", "TO_CITY_NAME" to "示例市", "TO_TOWN_NAME" to "西区",
        ))!!
        assertTrue(destination.region)
        assertNull(destination.latitude)
        assertEquals("示例市西区", destination.name)
        assertEquals("SEND TO_CITY_NAME+TO_TOWN_NAME+TO_AD_NAME", destination.source)
        assertEquals("maps:?daddr=%E7%A4%BA%E4%BE%8B%E5%B8%82%E8%A5%BF%E5%8C%BA&dirflg=d",
            HeadUnitMapDestination.Link.APPLE_MAPS.url(destination))
        assertTrue(HeadUnitMapDestination.Link.AMAP_PATH.url(destination)!!.startsWith("iosamap://path?"))
    }

    @Test fun aRegionNeverReplacesAPoint() {
        val trip = mapOf("KEY_TYPE" to 10002, "TO_CITY_NAME" to "示例市", "TO_TOWN_NAME" to "西区")
        HeadUnitMapDestination.record(send, trip)
        assertTrue(HeadUnitMapDestination.latest!!.region)
        HeadUnitMapDestination.record(send, mapOf("KEY_TYPE" to 10001, "ENDPOI_LATITUDE" to 34.5, "ENDPOI_LONGITUDE" to 114.5))
        HeadUnitMapDestination.record(send, trip)
        assertFalse(HeadUnitMapDestination.latest!!.region)
        assertEquals(34.5, HeadUnitMapDestination.latest!!.latitude!!, 1e-9)
    }

    /** The map keeps broadcasting the trip's region after guidance ends; it must not come back. */
    @Test fun aRegionDoesNotSurviveTheMapStoppingItsGuidance() {
        HeadUnitMapDestination.record(send, mapOf("KEY_TYPE" to 10001,
            "ENDPOI_LATITUDE" to 34.5, "ENDPOI_LONGITUDE" to 114.5))
        assertFalse(HeadUnitMapDestination.latest!!.region)
        HeadUnitMapDestination.markStoppedForTest()
        assertNull(HeadUnitMapDestination.latest)
        HeadUnitMapDestination.record(send, mapOf("KEY_TYPE" to 10002,
            "TO_CITY_NAME" to "示例市", "TO_TOWN_NAME" to "西区"))
        assertNull(HeadUnitMapDestination.latest)
        // A real new trip is a point, which ends the stopped state and is kept.
        HeadUnitMapDestination.record(send, mapOf("KEY_TYPE" to 10001,
            "ENDPOI_LATITUDE" to 33.4, "ENDPOI_LONGITUDE" to 113.4))
        assertEquals(33.4, HeadUnitMapDestination.latest!!.latitude!!, 1e-9)
    }

    /** State flags carry their value, so a report can tell a navigation start from an end. */
    @Test fun stateFlagsAreRecordedWithTheirValueAndPlacesAreNot() {
        val signature = HeadUnitMapDestination.signature(send, mapOf(
            "KEY_TYPE" to 10019, "EXTRA_STATE" to 2, "EXTRA_IS_NAVI_LIFECYCLE" to true,
            "TO_CITY_NAME" to "示例市",
        ))
        assertTrue(signature, signature.contains("EXTRA_STATE=2"))
        assertTrue(signature, signature.contains("EXTRA_IS_NAVI_LIFECYCLE=true"))
        assertTrue(signature, signature.contains("TO_CITY_NAME(String)"))
        assertFalse(signature, signature.contains("示例市"))
    }

    @Test fun navigationStateBroadcastHasNoDestination() {
        assertNull(HeadUnitMapDestination.destination(send, mapOf(
            "KEY_TYPE" to 10019, "EXTRA_STATE" to 1, "EXTRA_IS_FOREGROUND" to 1,
            "EXTRA_IS_NAVI_LIFECYCLE" to true, "IS_BYD_MAP" to true,
        )))
    }

    @Test fun carPositionIsNotADestination() {
        assertNull(HeadUnitMapDestination.destination(send, mapOf(
            "KEY_TYPE" to 10001, "CAR_LATITUDE" to 31.0, "CAR_LONGITUDE" to 121.0, "CUR_ROAD_NAME" to "Main",
        )))
    }

    @Test fun guidanceWithEndPointIsFound() {
        val destination = HeadUnitMapDestination.destination(send, mapOf(
            "KEY_TYPE" to 10001, "CAR_LATITUDE" to 31.0, "CAR_LONGITUDE" to 121.0,
            "ENDPOI_LATITUDE" to 31.5, "ENDPOI_LONGITUDE" to 121.5, "ENDPOI_NAME" to "Home",
        ))!!
        assertEquals(31.5, destination.latitude!!, 1e-9)
        assertEquals("Home", destination.name)
    }

    @Test fun implausibleCoordinatesAreIgnored() {
        assertNull(HeadUnitMapDestination.destination(send, mapOf("END_LAT" to 0.0, "END_LON" to 0.0)))
        assertNull(HeadUnitMapDestination.destination(send, mapOf("END_LAT" to 31_000_000, "END_LON" to 121_000_000)))
    }

    @Test fun jsonExtrasAreSearched() {
        val values = HeadUnitMapDestination.flatten(mapOf(
            "KEY_TYPE" to 12205,
            "EXTRA_ROUTE_INFO" to """{"destination":{"lat":30.1,"lon":120.2,"name":"Office"}}""",
        ))
        val destination = HeadUnitMapDestination.destination(send, values)!!
        assertEquals(30.1, destination.latitude!!, 1e-9)
        assertEquals(120.2, destination.longitude!!, 1e-9)
        assertEquals("Office", destination.name)
    }

    @Test fun reportListsKeysWithoutPlacesOrCoordinates() {
        HeadUnitMapDestination.record(recv, mapOf("KEY_TYPE" to 10038, "LAT" to 33.5, "LON" to 113.5, "POINAME" to "Secret place"))
        HeadUnitMapDestination.record(recv, mapOf("KEY_TYPE" to 10038, "LAT" to 33.5, "LON" to 113.5, "POINAME" to "Secret place"))
        val report = HeadUnitMapDestination.report()
        assertNotNull(HeadUnitMapDestination.latest)
        assertTrue(report, report.contains("x2 RECV keyType=10038"))
        assertTrue(report, report.contains("POINAME(String)"))
        assertFalse(report, report.contains("33.5"))
        assertFalse(report, report.contains("Secret"))
        report.lines().forEach { assertNotNull(it, DiagnosticRedactor.redact(it)) }
    }

    @Test fun linksPassCoordinatesUnchanged() {
        val destination = HeadUnitMapDestination.Destination(33.5, 113.5, "Destination", 10038, "test")
        assertEquals("maps:?daddr=33.500000,113.500000&dirflg=d", HeadUnitMapDestination.Link.APPLE_MAPS.url(destination))
        assertEquals("https://maps.apple.com/?daddr=33.500000,113.500000&dirflg=d",
            HeadUnitMapDestination.Link.APPLE_MAPS_WEB.url(destination))
        assertEquals("iosamap://path?sourceApplication=DiPlay&dlat=33.500000&dlon=113.500000" +
            "&dname=Destination&dev=0&t=0",
            HeadUnitMapDestination.Link.AMAP_PATH.url(destination))
        assertEquals("amapuri://route/plan/?sourceApplication=DiPlay&dlat=33.500000&dlon=113.500000" +
            "&dname=Destination&dev=0&t=0",
            HeadUnitMapDestination.Link.AMAP_URI.url(destination))
    }

    /** AMap reports that the app needs an update for an undocumented path, so only documented URIs are sent. */
    @Test fun amapLinksUseDocumentedUris() {
        val region = HeadUnitMapDestination.Destination(null, null, "示例市西区", 10002, "test")
        val point = HeadUnitMapDestination.Destination(33.5, 113.5, null, 10001, "test")
        listOf(HeadUnitMapDestination.Link.AMAP_PATH, HeadUnitMapDestination.Link.AMAP_URI).forEach { link ->
            listOf(region, point).forEach { destination ->
                val url = link.url(destination)!!
                assertTrue(url, url.startsWith("iosamap://path?") || url.startsWith("amapuri://route/plan/?"))
                assertTrue(url, url.contains("sourceApplication=DiPlay") && url.endsWith("&dev=0&t=0"))
            }
        }
        assertEquals("iosamap://path?sourceApplication=DiPlay" +
            "&dname=%E7%A4%BA%E4%BE%8B%E5%B8%82%E8%A5%BF%E5%8C%BA&dev=0&t=0",
            HeadUnitMapDestination.Link.AMAP_PATH.url(region))
    }

    /** iosamap://navi documents lat/lon as required, so a region cannot use it. */
    @Test fun startingGuidanceAtOnceNeedsACoordinate() {
        val point = HeadUnitMapDestination.Destination(32.222222, 112.222222, "示例地点乙", 10001, "test")
        assertEquals("iosamap://navi?sourceApplication=DiPlay&lat=32.222222&lon=112.222222" +
            "&poiname=%E7%A4%BA%E4%BE%8B%E5%9C%B0%E7%82%B9%E4%B9%99&dev=0&style=0",
            HeadUnitMapDestination.Link.AMAP_NAVI.url(point))
        val region = HeadUnitMapDestination.Destination(null, null, "示例市西区", 10002, "test")
        assertNull(HeadUnitMapDestination.Link.AMAP_NAVI.url(region))
        assertTrue(HeadUnitMapDestination.Link.AMAP_NAVI.needsCoordinates)
        HeadUnitMapDestination.Link.entries.filter { it != HeadUnitMapDestination.Link.AMAP_NAVI }.forEach {
            assertFalse(it.name, it.needsCoordinates)
            assertNotNull(it.name, it.url(region))
        }
    }

    /** Apple's maps links chain stops in daddr; AMap's documented URIs take one destination only. */
    @Test fun viaPointsAreChainedOnlyIntoTheLinksThatCarryThem() {
        val destination = HeadUnitMapDestination.Destination(33.5, 113.5, "Destination", null, "test",
            listOf(HeadUnitMapDestination.Waypoint("Charger", 33.1, 113.1),
                HeadUnitMapDestination.Waypoint(null, 33.2, 113.2)))
        assertEquals("maps:?daddr=33.100000,113.100000+to:33.200000,113.200000+to:33.500000,113.500000&dirflg=d",
            HeadUnitMapDestination.Link.APPLE_MAPS.url(destination))
        assertEquals("maps:?daddr=33.500000,113.500000&dirflg=d",
            HeadUnitMapDestination.Link.APPLE_MAPS.url(destination, includeVia = false))
        HeadUnitMapDestination.Link.entries.filter { !it.carriesVia }.forEach { link ->
            assertFalse(link.name, link.url(destination)!!.contains("+to:"))
        }
        // A route without via points is unchanged, so the usual single-stop link stays the same.
        assertEquals(HeadUnitMapDestination.Link.APPLE_MAPS.url(destination.copy(via = emptyList())),
            HeadUnitMapDestination.Link.APPLE_MAPS.url(destination, includeVia = false))
    }

    /**
     * AMap's URI documentation asks for the POI id in preference to a coordinate.
     */
    @Test fun amapLinksCarryThePoiIdAlongsideTheCoordinate() {
        val destination = HeadUnitMapDestination.Destination(30.123457, 110.987654, "示例地点甲", null, "test",
            id = "B0TESTPOI1")
        val navi = HeadUnitMapDestination.Link.AMAP_NAVI.url(destination)!!
        assertTrue(navi, navi.contains("&poiid=B0TESTPOI1"))
        assertTrue(navi, navi.contains("lat=30.123457"))
        val path = HeadUnitMapDestination.Link.AMAP_PATH.url(destination)!!
        assertTrue(path, path.contains("&did=B0TESTPOI1"))
        assertTrue(path, path.contains("&dlat=30.123457"))
        // Apple has no such notion, so its links are unchanged by the id.
        assertEquals(HeadUnitMapDestination.Link.APPLE_MAPS.url(destination.copy(id = null)),
            HeadUnitMapDestination.Link.APPLE_MAPS.url(destination))
    }

    /** The firmware that logs a name and an id but no coordinate still aims at the exact place. */
    @Test fun aDestinationKnownOnlyByIdStillRoutesWithAmap() {
        val destination = HeadUnitMapDestination.Destination(null, null, "示例地点甲", null, "test",
            id = "B0TESTPOI1")
        assertTrue(destination.region)
        assertTrue(destination.exact)
        val path = HeadUnitMapDestination.Link.AMAP_PATH.url(destination)!!
        assertTrue(path, path.contains("&did=B0TESTPOI1"))
        assertFalse(path, path.contains("dlat="))
        // navi needs a coordinate whatever else is known, so it still declines.
        assertNull(HeadUnitMapDestination.Link.AMAP_NAVI.url(destination))
    }

    /** A broadcast region has neither coordinate nor id, so it is still only a district. */
    @Test fun aRegionIsNotExact() {
        val region = HeadUnitMapDestination.Destination(null, null, "示例市西区", null, "test")
        assertTrue(region.region)
        assertFalse(region.exact)
    }

    /** AMap's route planning takes via points as four parallel lists of the same length. */
    @Test fun amapRoutePlanningCarriesViaPointsAsParallelLists() {
        val destination = HeadUnitMapDestination.Destination(33.5, 113.5, "Destination", null, "test",
            listOf(HeadUnitMapDestination.Waypoint("Charger", 33.1, 113.1),
                HeadUnitMapDestination.Waypoint(null, 33.2, 113.2)))
        val path = HeadUnitMapDestination.Link.AMAP_PATH.url(destination)!!
        assertTrue(path, path.contains("&vian=2"))
        assertTrue(path, path.contains("&vialons=113.100000|113.200000"))
        assertTrue(path, path.contains("&vialats=33.100000|33.200000"))
        assertTrue(path, path.contains("&vianames=Charger|"))
        // Turning them off leaves the destination alone.
        assertFalse(HeadUnitMapDestination.Link.AMAP_PATH.url(destination, includeVia = false)!!.contains("vian="))
    }

    @Test fun sendingARegionWithANaviLinkReportsTheMissingCoordinate() {
        HeadUnitMapDestination.record(send, mapOf("KEY_TYPE" to 10002, "TO_CITY_NAME" to "示例市"))
        assertEquals(HeadUnitMapDestination.SendResult.NEEDS_COORDINATES,
            HeadUnitMapDestination.send(HeadUnitMapDestination.Link.AMAP_NAVI))
        // No destination at all is reported as such, whatever the link needs.
        HeadUnitMapDestination.resetForTest()
        assertEquals(HeadUnitMapDestination.SendResult.NO_DESTINATION,
            HeadUnitMapDestination.send(HeadUnitMapDestination.Link.AMAP_NAVI))
    }
}
