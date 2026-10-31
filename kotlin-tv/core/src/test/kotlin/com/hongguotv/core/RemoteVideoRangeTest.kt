package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RemoteVideoRangeTest {
    private fun box(type: String, body: ByteArray) = be32(body.size + 8) + type.toByteArray() + body

    private fun fixture(): ByteArray {
        val ftyp = box("ftyp", "isom0000".toByteArray())
        val moov = box("moov", byteArrayOf())
        val mdat = box("mdat", ByteArray(64) { (it + 1).toByte() })
        return ftyp + moov + mdat
    }

    private fun serverFor(data: ByteArray, responseCode: Int = 206, wrongRange: Boolean = false): MockWebServer {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val requested = request.getHeader("Range") ?: return MockResponse().setResponseCode(400)
                val match = Regex("bytes=(\\d+)-(\\d+)").matchEntire(requested)
                    ?: return MockResponse().setResponseCode(400)
                val start = match.groupValues[1].toInt()
                val requestedEnd = match.groupValues[2].toInt()
                val end = minOf(requestedEnd, data.lastIndex)
                if (start !in data.indices || end < start) return MockResponse().setResponseCode(416)
                val body = data.copyOfRange(start, end + 1)
                val actualStart = if (wrongRange) start + 1 else start
                return MockResponse()
                    .setResponseCode(responseCode)
                    .setHeader("Content-Range", "bytes $actualStart-$end/${data.size}")
                    .setHeader("Content-Length", body.size)
                    .setBody(Buffer().write(body))
            }
        }
        server.start()
        return server
    }

    @Test fun readsAcrossChunkBoundariesAndReturnsShortFinalRange() {
        val bytes = fixture()
        val server = serverFor(bytes)
        val video = RemoteVideo(
            OkHttpClient(),
            StreamInfo(server.url("/video").toString(), null, "test"),
            chunkBytes = 32,
            aheadBytes = 0
        )
        try {
            video.prepare()
            assertEquals(bytes.size.toLong(), video.total)
            assertArrayEquals(bytes.copyOfRange(21, 53), video.read(21, 32))
            assertArrayEquals(bytes.copyOfRange(53, 58), video.read(53, 5))
            assertArrayEquals(bytes.copyOfRange(88, bytes.size), video.read(88, 20))
            assertTrue(video.read(bytes.size.toLong(), 10).isEmpty())
            assertTrue(video.stats().fetchedBytes > 0)
        } finally {
            video.close()
            server.shutdown()
        }
    }


    @Test fun repeatedReadsAndBackwardSeekReuseCachedChunks() {
        val bytes = fixture()
        val server = serverFor(bytes)
        val video = RemoteVideo(
            OkHttpClient(),
            StreamInfo(server.url("/video").toString(), null, "test"),
            chunkBytes = 32,
            aheadBytes = 0
        )
        try {
            video.prepare()
            assertArrayEquals(bytes.copyOfRange(64, 80), video.read(64, 16))
            val requestsAfterForwardRead = server.requestCount
            assertArrayEquals(bytes.copyOfRange(0, 16), video.read(0, 16))
            assertArrayEquals(bytes.copyOfRange(64, 80), video.read(64, 16))
            assertEquals("cached chunks should not trigger duplicate HTTP ranges", requestsAfterForwardRead, server.requestCount)
        } finally {
            video.close()
            server.shutdown()
        }
    }

    @Test fun rejectsResourceWhoseLengthChangesBetweenRanges() {
        val original = fixture()
        val changed = original + byteArrayOf(99)
        val server = MockWebServer()
        var requests = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests++
                val range = Regex("bytes=(\\d+)-(\\d+)").matchEntire(request.getHeader("Range").orEmpty())
                    ?: return MockResponse().setResponseCode(400)
                val start = range.groupValues[1].toInt()
                val data = if (requests == 1) original else changed
                val end = minOf(range.groupValues[2].toInt(), data.lastIndex)
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/${data.size}")
                    .setBody(Buffer().write(data.copyOfRange(start, end + 1)))
            }
        }
        server.start()
        val video = RemoteVideo(
            OkHttpClient(),
            StreamInfo(server.url("/video").toString(), null, "test"),
            chunkBytes = 32,
            aheadBytes = 0
        )
        try {
            video.prepare()
            try {
                video.read(32, 16)
                fail("changed resource length must be rejected")
            } catch (e: IOException) {
                assertTrue(e.message.orEmpty().contains("资源已变化"))
            }
        } finally {
            video.close()
            server.shutdown()
        }
    }

    @Test fun rejectsTruncatedRangeBody() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-31/96")
                .setHeader("Content-Length", "32")
                .setBody(Buffer().write(ByteArray(16)))
        )
        server.start()
        val video = RemoteVideo(
            OkHttpClient(),
            StreamInfo(server.url("/video").toString(), null, "test"),
            chunkBytes = 32,
            aheadBytes = 0
        )
        try {
            try {
                video.prepare()
                fail("truncated range body must be rejected")
            } catch (e: IOException) {
                assertTrue(e.message.orEmpty().isNotBlank())
            }
        } finally {
            video.close()
            server.shutdown()
        }
    }

    @Test fun rejectsServerThatIgnoresRangeAndReturnsFullResponse() {
        val server = serverFor(fixture(), responseCode = 200)
        val video = RemoteVideo(
            OkHttpClient(),
            StreamInfo(server.url("/video").toString(), null, "test"),
            chunkBytes = 32,
            aheadBytes = 0
        )
        try {
            try {
                video.prepare()
                fail("HTTP 200 must not be accepted for a byte-range request")
            } catch (e: IOException) {
                assertTrue(e.message.orEmpty().contains("HTTP 200"))
            }
        } finally {
            video.close()
            server.shutdown()
        }
    }

    @Test fun rejectsMismatchedContentRangeStart() {
        val server = serverFor(fixture(), wrongRange = true)
        val video = RemoteVideo(
            OkHttpClient(),
            StreamInfo(server.url("/video").toString(), null, "test"),
            chunkBytes = 32,
            aheadBytes = 0
        )
        try {
            try {
                video.prepare()
                fail("A mismatched Content-Range must be rejected")
            } catch (e: IOException) {
                assertTrue(e.message.orEmpty().contains("范围不匹配"))
            }
        } finally {
            video.close()
            server.shutdown()
        }
    }
}
