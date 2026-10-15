package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BydMapDestinationLogTest {
    // Taken from a DiLink 5 head unit (Di150VCP, Android 14) while its map guided to a place.
    private val line = "10-10 13:18:41.719 E/BaseNaviPresenter(28730): [main]BaseNaviPresenter " +
        "mRouteCarResult:RouteCarResultData{mMidPois=[], mFromPoi=POI{id='', name='My location', " +
        "point=GeoPoint{lon=111.11111111111111, lat=31.11111111111111}}, mToPoi=POI{id='B0TESTPOI1', " +
        "name='Gate', point=GeoPoint{lon=110.98765432109876, lat=30.12345678901234}}, mIsLocal=false, " +
        "mFocusIndex=0, routeType=1}"

    private fun withVia(vararg via: String) = line.replace("mMidPois=[]", "mMidPois=[" + via.joinToString(", ") + "]")

    private fun route(output: String) =
        (BydMapDestinationLog.parse(output) as BydMapDestinationLog.Event.Route).entry

    private fun poi(id: String, name: String, lon: String, lat: String) =
        "POI{id='$id', name='$name', point=GeoPoint{lon=$lon, lat=$lat}}"

    @Test fun readsTheRoutesEndAndNotItsStart() {
        val entry = route(line)
        assertEquals(30.12345678901234, entry.destination.latitude!!, 1e-12)
        assertEquals(110.98765432109876, entry.destination.longitude!!, 1e-12)
        assertEquals("Gate", entry.destination.name)
        assertEquals("B0TESTPOI1", entry.destination.id)
        assertEquals("10-10 13:18:41.719", entry.at)
        assertTrue(entry.via.isEmpty())
    }

    @Test fun readsTheViaPointsInOrder() {
        val entry = route(withVia(
            poi("B0FFH", "Charger", "111.30", "31.98"),
            poi("B0FFI", "Lunch", "111.35", "31.95"),
        ))
        assertEquals(listOf("Charger", "Lunch"), entry.via.map { it.name })
        assertEquals(111.30, entry.via.first().longitude!!, 1e-12)
        assertEquals(31.95, entry.via.last().latitude!!, 1e-12)
        // The start is in the same line, after the via list, and must not be taken for a via point.
        assertEquals("Gate", entry.destination.name)
    }

    @Test fun aViaNameHoldingABracketDoesNotSwallowTheRest() {
        val entry = route(withVia(poi("B0FFH", "Lot [A]", "111.30", "31.98")))
        assertEquals(listOf("Lot [A]"), entry.via.map { it.name })
        assertEquals("Gate", entry.destination.name)
    }

    @Test fun theNewestRouteWins() {
        // A different place: the POI id identifies it, so changing the name alone would not.
        val older = line.replace("id='B0TESTPOI1'", "id='B000EARLIER'").replace("name='Gate'", "name='Earlier'")
        assertEquals("Gate", route("$older\n$line").destination.name)
        assertEquals(2, BydMapDestinationLog.parseAll("$older\n$line").size)
    }

    /** The id identifies the place, so the same id twice is one route however it is named. */
    @Test fun theSameIdUnderTwoNamesIsOneRoute() {
        val renamed = line.replace("name='Gate'", "name='Gate (east)'")
        assertEquals(1, BydMapDestinationLog.parseAll("$renamed\n$line").size)
    }

    /**
     * Was ignored until a probe showed this is the one marker the car logs for every route planned.
     * The start POI in the same line is the car's own position and must not become the destination.
     */
    @Test fun theRouteRequestIsReadAndItsStartIsNot() {
        val entry = route("10-10 13:18:39.489 E/ModuleDriveServiceImpl(28730): [main][buildRequestParam] " +
            "start = POI{id='', name='My location'}, end = GateB0TESTPOI1, via = []")
        assertEquals("Gate", entry.destination.name)
        assertEquals("B0TESTPOI1", entry.destination.id)
        assertNull(entry.destination.latitude)
    }

    @Test fun aLineWithoutARouteIsIgnored() {
        assertNull(BydMapDestinationLog.parse(
            "10-10 13:18:39.489 E/NaviService(28730): [main]NavExtraDataControl update"))
    }

    @Test fun anEmptyIslandIsNotAPlace() {
        assertNull(BydMapDestinationLog.parse(
            line.replace("lon=110.98765432109876, lat=30.12345678901234", "lon=0.0, lat=0.0")))
    }

    @Test fun theWindowIsTheOnlyPartOfTheCommandThatVaries() {
        assertEquals("logcat -d -v time -T '10-10 13:18:41.719' | grep -E 'mToPoi=PO[I]|" +
            "storeRouteCarResultDat[a]|createEndPoiInf[o]|buildRequestPara[m]|onNaviSto[p]|" +
            "clearRouteCarResultDat[a]' | grep -v -F 'DiPlay-MapDestination' | " +
            "grep -v -F 'auditd' | tail -60",
            BydMapDestinationLog.command("10-10 13:18:41.719"))
    }

    // The same Di150VCP after a firmware change: BYD's own navigation layer, no AutoSDK presenter.
    private val named = "10-11 09:35:08.292 E/NavigationUtil(10613): [main]storeRouteCarResultData: toPoi=示例地点甲"
    private val namedId =
        "10-11 09:35:08.300 E/MomentaUtil(10613): [SomeIpClient_Thread]createEndPoiInfo poiId:B0TESTPOI3 mapIdsByPoiId:[]"

    @Test fun aDestinationLoggedByNameOnlyIsRead() {
        val entry = route(named)
        assertEquals("示例地点甲", entry.destination.name)
        assertNull(entry.destination.latitude)
        assertNull(entry.destination.longitude)
        assertEquals("10-11 09:35:08.292", entry.at)
    }

    /** The id is logged on the line after the name, so it has to be carried across. */
    @Test fun theIdLoggedBeforeTheNameIsAttachedToIt() {
        assertEquals("B0TESTPOI3", route("$namedId\n$named").destination.id)
    }

    /** This firmware never logs onNaviStop; clearing the route is the only end-of-guidance marker. */
    @Test fun clearingTheRouteIsAStop() {
        assertTrue(BydMapDestinationLog.parse(
            "10-11 09:35:10.000 E/NavigationUtil(10613): [main]clearRouteCarResultData")
            is BydMapDestinationLog.Event.Stopped)
    }

    /** A point is richer, so where a firmware logs both the AutoSDK line must still win. */
    @Test fun aPointIsPreferredOverANameForTheSameRoute() {
        val entry = route("$named\n$line")
        assertEquals("Gate", entry.destination.name)
        assertEquals(30.12345678901234, entry.destination.latitude!!, 1e-12)
    }

    @Test fun theCommandLooksForBothLogShapesAndDropsDiPlaysOwnLines() {
        val command = BydMapDestinationLog.command("10-10 13:18:41.719")
        assertTrue(command, command.contains("storeRouteCarResultDat[a]"))
        assertTrue(command, command.contains("createEndPoiInf[o]"))
        assertTrue(command, command.contains("clearRouteCarResultDat[a]"))
        // Without this the probe reads back what it just logged, nesting on every poll.
        assertTrue(command, command.contains("grep -v -F 'DiPlay-MapDestination'"))
    }

    /** Its own read, so the wide pattern can never push a marker out of the markers' own tail. */
    @Test fun theProbeReadsSeparatelyFromTheMarkers() {
        val probe = BydMapDestinationLog.probeCommand("10-10 13:18:41.719")
        val markers = BydMapDestinationLog.command("10-10 13:18:41.719")
        assertTrue(probe, probe.contains("GeoPoin[t]"))
        assertFalse(markers, markers.contains("GeoPoin"))
        assertFalse(probe, probe.contains("mToPoi=PO"))
        assertTrue(probe, probe.contains("grep -v -F 'DiPlay-MapDestination'"))
        assertTrue(probe, probe.endsWith("tail -40"))
    }

    /**
     * The route request, which on a Di150VCP was logged for every route planned, where the richer
     * mToPoi line was not. The name and the id run together with nothing between them.
     */
    private val request = "10-10 13:19:09.416 E/ModuleDriveServiceImpl(28730): [main][buildRequestParam] " +
        "start = POI{id='', name='我的位置', point=GeoPoint{lon=111.11111111111111, lat=31.11111111111111}}, " +
        "end = 示例地点乙B0TESTPOI2, via = []"

    @Test fun theRouteRequestGivesTheNameAndTheIdButNoPoint() {
        val entry = route(request)
        assertEquals("示例地点乙", entry.destination.name)
        assertEquals("B0TESTPOI2", entry.destination.id)
        assertNull(entry.destination.latitude)
        // The car's own position is in the same line and must not be taken for the destination.
        assertTrue(entry.via.isEmpty())
    }

    /**
     * One route is logged several times within a second or two and only some of those lines carry the
     * coordinate, so the richest has to win rather than the last.
     */
    @Test fun aPointWinsOverALaterNameForTheSameRoute() {
        // The same place as `line`, logged by the component that writes no coordinate.
        val sameByName = named.replace("toPoi=示例地点甲", "toPoi=Gate")
        val entry = route("$line\n$sameByName")
        assertEquals(30.12345678901234, entry.destination.latitude!!, 1e-12)
        assertEquals("Gate", entry.destination.name)
    }

    /**
     * Verbatim from a Di150VCP: SELinux's auditd quoting the argv of the poll's own grep. It holds
     * every marker the pattern looks for, so it used to read as the map having stopped — 24 times in
     * one drive, while the car's navigation logged no stop at all.
     */
    private val auditEcho = "10-11 15:12:56.940 W/auditd  (  483): type=1309 audit(0.0:64589): argc=3 " +
        "a0=\"grep\" a1=\"-E\" a2=\"mToPoi=POI|storeRouteCarResultData|createEndPoiInfo|" +
        "buildRequestParam|onNaviStop|clearRouteCarResultData\""

    @Test fun anAuditRecordQuotingTheCommandIsNotAStop() {
        assertTrue(BydMapDestinationLog.quotesACommand(auditEcho))
        assertNull(BydMapDestinationLog.parse(auditEcho))
        assertTrue(BydMapDestinationLog.parseAll(auditEcho).isEmpty())
    }

    /** The echo must not end a route that is still being driven, which is what it used to do. */
    @Test fun aRouteSurvivesAnAuditRecordArrivingAfterIt() {
        val events = BydMapDestinationLog.parseAll("$line\n$auditEcho")
        assertEquals(1, events.size)
        assertEquals("Gate", (events.single() as BydMapDestinationLog.Event.Route).entry.destination.name)
    }

    /** A real navigation line is never mistaken for one, however the two sit together. */
    @Test fun realNavigationLinesAreNotTakenForCommandEchoes() {
        listOf(line, named, namedId, request).forEach {
            assertFalse(it, BydMapDestinationLog.quotesACommand(it))
        }
        stop.lineSequence().forEach { assertFalse(it, BydMapDestinationLog.quotesACommand(it)) }
    }

    /**
     * The pattern must not match its own text, so that any channel echoing the command — not only the
     * one auditd was caught using — cannot feed a marker back.
     */
    @Test fun theShellPatternCannotMatchItsOwnText() {
        val pattern = BydMapDestinationLog.selfExcluding("onNaviStop|clearRouteCarResultData")
        // Still matches what it is meant to match.
        assertTrue(pattern, Regex(pattern).containsMatchIn("E/NaviController(1): [TBTNotify]onNaviStop:0,0"))
        assertTrue(pattern, Regex(pattern).containsMatchIn("E/NavigationUtil(1): [main]clearRouteCarResultData"))
        // And no longer matches the command that carries it.
        assertFalse(pattern, Regex(pattern).containsMatchIn("a2=\"$pattern\""))
    }

    @Test fun theCommandCarriesNoPatternThatCanMatchItself() {
        val command = BydMapDestinationLog.command("10-11 15:12:56.940")
        val pattern = Regex(command.substringAfter("grep -E '").substringBefore("'"))
        assertFalse(command, pattern.containsMatchIn(command))
        // The channel that was caught doing it is dropped outright as well.
        assertTrue(command, command.contains("grep -v -F 'auditd'"))
    }

    /**
     * Following the log: logcat filters by itself, so no pipe stage can buffer a stop, and the
     * pattern still cannot match the audit record of the command that carries it.
     */
    @Test fun theFollowCommandFiltersInLogcatAndCannotMatchItself() {
        for (probe in listOf(false, true)) {
            val command = BydMapDestinationLog.followCommand("10-12 19:00:11.000", probe)
            assertFalse(command, command.contains("|  grep") || command.contains("| grep") || command.contains(" -d "))
            assertTrue(command, command.startsWith("logcat -v time -T '10-12 19:00:11.000' -e '"))
            val pattern = Regex(command.substringAfter("-e '").substringBefore("'"))
            assertFalse(command, pattern.containsMatchIn(command))
            assertTrue(pattern.containsMatchIn("E/NaviController(1): [TBTNotify]onNaviStop:0,0"))
            assertTrue(pattern.containsMatchIn(line))
            assertEquals(probe, pattern.containsMatchIn("E/X(1): GeoPoint{lon=1.0, lat=2.0}"))
        }
    }

    /** A stop ends what came before it, so only what follows counts. */
    @Test fun onlyTheRoutesAfterTheLastStopAreConsidered() {
        assertTrue(BydMapDestinationLog.parse("$line\n$stop") is BydMapDestinationLog.Event.Stopped)
        assertEquals("示例地点乙", route("$line\n$stop\n$request").destination.name)
    }

    /**
     * The regression this guards: a window holding an old stop and the route that replaced it used to
     * yield the route only, so the next window — still holding the stop within its overlap — yielded
     * the stop and ended CarPlay's guidance while the car was still navigating. Both are reported now,
     * in order, and the caller acts on each.
     */
    @Test fun aStopAndTheRouteAfterItAreBothReportedInOrder() {
        val events = BydMapDestinationLog.parseAll("$stop\n$line")
        assertEquals(2, events.size)
        assertTrue(events[0] is BydMapDestinationLog.Event.Stopped)
        assertEquals("Gate", (events[1] as BydMapDestinationLog.Event.Route).entry.destination.name)
    }

    /** One route logged many times stays one event, so a window is not a burst of hand-overs. */
    @Test fun repeatsOfOneRouteCollapseToItsRichestLine() {
        val sameByName = named.replace("toPoi=示例地点甲", "toPoi=Gate")
        val events = BydMapDestinationLog.parseAll("$sameByName\n$line\n$sameByName")
        assertEquals(1, events.size)
        val entry = (events.single() as BydMapDestinationLog.Event.Route).entry
        // Same place throughout; the line carrying the coordinate is the one kept.
        assertEquals(30.12345678901234, entry.destination.latitude!!, 1e-12)
    }

    /** Two different destinations in one window are two routes, not one. */
    @Test fun twoDestinationsInOneWindowAreTwoRoutes() {
        val events = BydMapDestinationLog.parseAll("$line\n$request")
        assertEquals(2, events.size)
        assertEquals("Gate", (events[0] as BydMapDestinationLog.Event.Route).entry.destination.name)
        assertEquals("示例地点乙", (events[1] as BydMapDestinationLog.Event.Route).entry.destination.name)
    }

    /** The newest event is still what a single-event caller gets. */
    @Test fun parseStillYieldsTheNewestEvent() {
        assertEquals("示例地点乙", route("$stop\n$line\n$request").destination.name)
        assertTrue(BydMapDestinationLog.parse("$line\n$request\n$stop") is BydMapDestinationLog.Event.Stopped)
    }

    /** The whole point of the shape: a shareable report must not carry where the driver went. */
    @Test fun aShapeKeepsTheStructureAndNoneOfTheContent() {
        val shape = BydMapDestinationLog.shape(
            "10-10 13:18:41.719 E/BaseNaviPresenter(28730): mToPoi=POI{id='B0TESTPOI1', " +
                "name='示例地点乙', point=GeoPoint{lon=110.98765432109876, lat=30.12345678901234}}")
        assertFalse(shape, shape.contains("示例地点乙"))
        assertFalse(shape, shape.contains("B0TESTPOI1"))
        assertFalse(shape, shape.contains("110.98"))
        assertFalse(shape, shape.contains("30.123"))
        // What a parser has to match survives.
        assertTrue(shape, shape.contains("mToPoi=POI{id="))
        assertTrue(shape, shape.contains("point=GeoPoint{lon=#.#, lat=#.#}"))
        assertTrue(shape, shape.contains("E/BaseNaviPresenter"))
    }

    @Test fun aNameOutsideQuotesIsDroppedToo() {
        val shape = BydMapDestinationLog.shape("10-10 13:18:41.719 E/Navi(1): dest=示例市东区 code=100001")
        assertFalse(shape, shape.contains("示例市东区"))
        assertFalse(shape, shape.contains("440305"))
        assertTrue(shape, shape.contains("dest="))
    }

    /** Short integers are state codes, which are what distinguishes one line's meaning from another. */
    @Test fun shortIntegersSurviveBecauseTheyAreStateNotPlace() {
        val shape = BydMapDestinationLog.shape("10-10 13:18:41.719 E/Navi(1): KEY_TYPE=10019 state=2")
        assertTrue(shape, shape.contains("KEY_TYPE=10019"))
        assertTrue(shape, shape.contains("state=2"))
    }

    @Test fun recognisedLinesAreNotReportedAsCandidates() {
        assertTrue(BydMapDestinationLog.candidates("$line\n$stop").isEmpty())
    }

    /**
     * Both forms travel: the shape for the session log, which a shared report carries, and the line
     * itself for the navigation buffer, which the report carries in a section named for it.
     */
    @Test fun anUnknownLineCarriesBothItsTextAndItsShapeAndIsDeduplicated() {
        val unknown = "10-10 13:20:00.000 E/NaviPresenter(1): mDestPoi=Poi{name='家', " +
            "pos=LatLng{lat=31.5, lng=111.5}}"
        val candidates = BydMapDestinationLog.candidates("$unknown\n$unknown\n$line")
        assertEquals(1, candidates.size)
        val candidate = candidates.single()
        assertEquals(unknown, candidate.raw)
        assertFalse(candidate.shape, candidate.shape.contains("家"))
        assertTrue(candidate.shape, candidate.shape.contains("mDestPoi=Poi{name="))
    }

    /**
     * Deduplication is on the shape, so one real example of each distinct format is kept. A quoted
     * value becomes its length, so two names of the same length share a shape and one is enough.
     */
    @Test fun linesOfTheSameShapeKeepOneExample() {
        val first = "10-10 13:20:00.000 E/NaviPresenter(1): mDestPoi=Poi{name='家'}"
        val second = "10-10 13:20:05.000 E/NaviPresenter(1): mDestPoi=Poi{name='店'}"
        val candidates = BydMapDestinationLog.candidates("$first\n$second")
        assertEquals(1, candidates.size)
        assertEquals(first, candidates.single().raw)
    }

    @Test fun theNumberOfCandidatesPerPollIsBounded() {
        val many = (1..30).joinToString("\n") { "10-10 13:20:00.000 E/Navi($it): mDestPoi=$it" }
        assertTrue(BydMapDestinationLog.candidates(many).size <= 6)
    }

    // One stop writes these lines together; what matters is the newest event in the window.
    private val stop = "10-10 13:18:36.952 E/NaviController(28730): [TBTNotify]onNaviStop:0,0\n" +
        "10-10 13:18:36.952 E/BaseNaviPresenter(28730): [main]onNaviStop\n" +
        "10-10 13:18:36.959 E/NaviService(28730): [main]onNaviStop, hide NaviFloatWindow"

    /**
     * These tests run on the desktop JVM, whose regex engine accepts a dangling `}` or `]`. Android's
     * is ICU-backed and throws on one, which took down this object's initializer on the car and left
     * the feature dead while every test passed. Guard the escaping directly.
     */
    @Test fun everyBraceAndBracketIsEscapedForAndroidsRegexEngine() {
        val patterns = mapOf(
            "POI" to BydMapDestinationLog.POI.pattern,
            "ROUTE" to BydMapDestinationLog.ROUTE.pattern,
            "VIA" to BydMapDestinationLog.VIA.pattern,
            "LINE_TIME" to BydMapDestinationLog.LINE_TIME.pattern,
        )
        for ((name, pattern) in patterns) {
            var index = 0
            while (index < pattern.length) {
                val character = pattern[index]
                if (character == '\\') { index += 2; continue }
                if (character == '{' || character == '}') {
                    // A quantifier such as {2} or {2,3} is the only unescaped brace allowed.
                    val quantifier = Regex("\\{\\d+(,\\d*)?\\}").matchAt(pattern, index)
                    assertTrue("$name has an unescaped '$character' at $index: $pattern", quantifier != null)
                    index += quantifier!!.value.length
                    continue
                }
                if (character == '[') {
                    val close = pattern.indexOf(']', index)
                    assertTrue("$name has an unclosed '[' at $index: $pattern", close > index)
                    index = close + 1
                    continue
                }
                assertTrue("$name has a dangling ']' at $index: $pattern", character != ']')
                index++
            }
        }
    }

    @Test fun guidanceEndingIsReported() {
        assertTrue(BydMapDestinationLog.parse(stop) is BydMapDestinationLog.Event.Stopped)
    }

    @Test fun aStopFollowedByANewRouteReadsAsTheRoute() {
        assertEquals("Gate", route("$stop\n$line").destination.name)
    }

    @Test fun aRouteTheDriverThenCancelledReadsAsStopped() {
        assertTrue(BydMapDestinationLog.parse("$line\n$stop") is BydMapDestinationLog.Event.Stopped)
    }
}
