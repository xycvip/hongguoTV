package com.hongguotv.core

import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OfficialCommentRepositoryTest {
    private val repository = OfficialCommentRepository(
        OkHttpClient(),
        PlaybackDeviceSession { null },
        "https://example.invalid",
    )

    @Test fun ordinaryCommentParserKeepsIdsAsStringsAndReadsOfficialFields() {
        val payload = JSONObject(
            """{"comment_id":"7691344565400093502","common":{"content":{"text":"这一集太精彩了"},"create_timestamp":1790784507,"user_info":{"base_info":{"user_name":"观众甲","expand_user_avatar":"https://img.example/avatar.jpg"}}},"stat":{"digg_count":17,"reply_count":3},"user_action":{"user_digg":1}}"""
        )
        val comment = repository.parseComment(payload)!!
        assertEquals("7691344565400093502", comment.id)
        assertEquals("观众甲", comment.author)
        assertEquals("这一集太精彩了", comment.text)
        assertEquals("https://img.example/avatar.jpg", comment.avatar)
        assertEquals(17, comment.likes)
        assertEquals(3, comment.replies)
        assertTrue(comment.liked)
    }

    @Test fun replyParserAcceptsOfficialUppercaseCommonShape() {
        val payload = JSONObject(
            """{"reply_id":"7691344565400093503","Common":{"content":{"text":"赞同"},"create_timestamp":1790784508,"user_info":{"base_info":{"user_name":"观众乙"}}},"stat":{"digg_count":2}}"""
        )
        val reply = repository.parseComment(payload, reply = true)!!
        assertEquals("7691344565400093503", reply.id)
        assertEquals("观众乙", reply.author)
        assertEquals("赞同", reply.text)
        assertFalse(reply.liked)
    }

    @Test fun malformedOfficialIdsAreIgnoredInsteadOfDisplayedAsComments() {
        assertNull(repository.parseComment(JSONObject("""{"comment_id":"not-a-platform-id","common":{"content":{"text":"无效"}}}""")))
    }

    @Test fun invalidCommentRowsAreSkipped() {
        assertNull(repository.parseComment(JSONObject("""{"comment_id":"0","common":{"content":{"text":"无效"}}}""")))
        assertNull(repository.parseComment(JSONObject("""{"comment_id":"123","common":{"content":{"text":" "}}}""")))
    }

    @Test fun guestsCannotPublishReplyOrLike() {
        assertThrows(java.io.IOException::class.java) { repository.publish(null, "123", "456", "游客评论") }
        assertThrows(java.io.IOException::class.java) { repository.reply(null, "123", "456", "789", "游客回复") }
        assertThrows(java.io.IOException::class.java) { repository.setLiked(null, "123", "789", true) }
    }

    @Test fun invalidEpisodeIdsAreRejectedBeforeAnyNetworkRequest() {
        assertThrows(IllegalArgumentException::class.java) { repository.list(null, "bad-id", "456") }
        assertThrows(IllegalArgumentException::class.java) { repository.list(null, "123", "bad-id") }
    }
}
