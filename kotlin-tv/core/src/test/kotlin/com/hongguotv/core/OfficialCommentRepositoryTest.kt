package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Test

class OfficialCommentRepositoryTest {
    @Test fun commentIdentifiersRemainStrings() {
        val id = "7691344565400093502"
        assertEquals(19, id.length)
        assertEquals("7691344565400093502", id)
    }
}
