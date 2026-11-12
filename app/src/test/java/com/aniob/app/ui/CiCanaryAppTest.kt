package com.aniob.app.ui

import org.junit.Assert.fail
import org.junit.Test

/** TEMPORARY: proves CI compiles and runs the app unit tests. Reverted in the next commit. */
class CiCanaryAppTest {
    @Test
    fun canary() {
        fail("CANARY-APP lane type = ${com.aniob.core.run.RunLane.FAST_LAUNCH}")
    }
}
