package com.qm.qqzygisk.hook.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtImageRkeyTest {
    @Test
    fun applyReplacesExistingRkey() {
        val url = "https://multimedia.nt.qq.com.cn/download?appid=1406&fileid=abc&rkey=OLD"
        val signed = NtImageRkey.apply(url, "&rkey=NEWTOKEN")
        assertEquals(
            "https://multimedia.nt.qq.com.cn/download?appid=1406&fileid=abc&rkey=NEWTOKEN",
            signed,
        )
    }

    @Test
    fun applyAppendsWhenMissing() {
        val url = "https://multimedia.nt.qq.com.cn/download?appid=1407&fileid=xyz"
        assertEquals("$url&rkey=TOKEN", NtImageRkey.apply(url, "rkey=TOKEN"))
    }

    @Test
    fun selectUsesAppidMapping() {
        val snapshot = RkeySnapshot(
            byType = mapOf(
                NtImageRkey.TYPE_PRIVATE to "&rkey=priv",
                NtImageRkey.TYPE_GROUP to "&rkey=grp",
            ),
            expiresAtMillis = Long.MAX_VALUE,
        )
        assertEquals(
            "&rkey=priv",
            NtImageRkey.select("https://multimedia.nt.qq.com.cn/download?appid=1406&fileid=a", snapshot),
        )
        assertEquals(
            "&rkey=grp",
            NtImageRkey.select("https://multimedia.nt.qq.com.cn/download?appid=1407&fileid=a", snapshot),
        )
    }

    @Test
    fun parseReadsTypeAndTtl() {
        val now = 1_700_000_000_000L
        val encoded = NtImageRkey.encodeResponse(
            listOf(
                NtImageRkey.RkeyFixture("&rkey=priv", type = 10, ttlSeconds = 1800),
                NtImageRkey.RkeyFixture("&rkey=grp", type = 20, ttlSeconds = 900),
            ),
        )
        val parsed = NtImageRkey.parse(encoded, now)
        assertEquals("&rkey=priv", parsed.byType[10])
        assertEquals("&rkey=grp", parsed.byType[20])
        assertEquals(now + 900_000L, parsed.expiresAtMillis)
    }

    @Test
    fun parseFallsBackToEntryOrderWithoutType() {
        val encoded = NtImageRkey.encodeResponse(
            listOf(
                NtImageRkey.RkeyFixture("&rkey=first"),
                NtImageRkey.RkeyFixture("&rkey=second"),
            ),
        )
        val parsed = NtImageRkey.parse(encoded, 0L)
        assertEquals("&rkey=first", parsed.byType[NtImageRkey.TYPE_PRIVATE])
        assertEquals("&rkey=second", parsed.byType[NtImageRkey.TYPE_GROUP])
    }

    @Test
    fun shouldRefreshNearExpiry() {
        val snapshot = RkeySnapshot(mapOf(10 to "&rkey=x"), expiresAtMillis = 10_000L)
        assertFalse(NtImageRkey.shouldRefresh(snapshot, 1_000L, skewMillis = 1_000L))
        assertTrue(NtImageRkey.shouldRefresh(snapshot, 9_500L, skewMillis = 1_000L))
        assertTrue(NtImageRkey.shouldRefresh(null, 0L))
    }

    @Test
    fun unpackWupStripsLengthPrefix() {
        val body = byteArrayOf(1, 2, 3)
        val packed = NtImageRkey.prefixWup(body)
        assertEquals(0, packed[0].toInt())
        assertTrue(NtImageRkey.unpackWup(packed).contentEquals(body))
        assertTrue(NtImageRkey.unpackWup(body).contentEquals(body))
    }

    @Test
    fun needsRkeyIgnoresLegacyCdn() {
        assertFalse(NtImageRkey.needsRkey("https://gchat.qpic.cn/gchatpic_new/0/0-0-ABC/0"))
        assertTrue(NtImageRkey.needsRkey("https://multimedia.nt.qq.com.cn/download?appid=1406"))
        assertTrue(NtImageRkey.needsRkey("/download?appid=1407&fileid=x"))
    }

    @Test
    fun scanRecoversRkeysWhenProtoLayoutChanges() {
        val packed = "xxrkey=PRIVTOKEN\u0000junk&rkey=GRPTOKEN\u0000".toByteArray(Charsets.ISO_8859_1)
        val parsed = NtImageRkey.scan(packed, 0L)
        assertEquals("&rkey=PRIVTOKEN", parsed.byType[NtImageRkey.TYPE_PRIVATE])
        assertEquals("&rkey=GRPTOKEN", parsed.byType[NtImageRkey.TYPE_GROUP])
    }

    @Test
    fun parseOrScanFallsBackToScan() {
        val packed = "Oidb junk rkey=SCANNED".toByteArray(Charsets.ISO_8859_1)
        val parsed = NtImageRkey.parseOrScan(packed, 0L)
        assertEquals("&rkey=SCANNED", parsed.byType[NtImageRkey.TYPE_PRIVATE])
    }

    @Test
    fun isRkeyCommandAcceptsFormattedNativeCmd() {
        assertTrue(NtImageRkey.isRkeyCommand("OidbSvcTrpcTcp.0x9067_202"))
        assertTrue(NtImageRkey.isRkeyCommand("OidbSvcTrpcTcp.0x9067_1"))
        assertFalse(NtImageRkey.isRkeyCommand("OidbSvcTrpcTcp.0x10d1"))
    }

    @Test
    fun signedCandidatesCoverMissingAndExistingRkey() {
        val snapshot = RkeySnapshot(
            byType = mapOf(NtImageRkey.TYPE_PRIVATE to "&rkey=priv"),
            expiresAtMillis = Long.MAX_VALUE,
        )
        val url = "https://multimedia.nt.qq.com.cn/download?appid=1406&fileid=abc"
        val candidates = NtImageRkey.signedCandidates(url, snapshot)
        assertTrue(candidates.contains("$url&rkey=priv"))
        assertTrue(candidates.contains(url))
    }
}
