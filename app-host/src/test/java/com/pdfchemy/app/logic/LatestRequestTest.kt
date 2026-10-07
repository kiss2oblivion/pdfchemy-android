package com.pdfchemy.app.logic

import org.junit.Assert.*
import org.junit.Test

class LatestRequestTest {
    @Test fun outOfOrderCompletionCannotPublishTheOlderRequest() {
        val requests = LatestRequest()
        val first = requests.begin()
        val second = requests.begin()
        assertTrue(requests.isCurrent(second))
        assertFalse(requests.isCurrent(first))
        requests.invalidate()
        assertFalse(requests.isCurrent(second))
        val retry = requests.begin()
        assertTrue(requests.isCurrent(retry))
        assertFalse(requests.isCurrent(first))
    }
}
