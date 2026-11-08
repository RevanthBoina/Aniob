package com.aniob.app.workflow

import androidx.test.core.app.ApplicationProvider
import com.aniob.app.AniobApplication
import com.aniob.app.ui.model.ConfirmDecision
import com.aniob.app.ui.model.ConfirmRequest
import com.aniob.core.workflow.OperationIdentity
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = AniobApplication::class, sdk = [33])
class ApprovalBindingTest {

    @Test
    fun testApprovalRequiresMatchingIdentity() = runTest {
        val controller = ApprovalController()
        val identity = OperationIdentity(
            taskId = "task_1",
            actionId = "action_1",
            generation = 1L,
            destinationKey = "https://example.com",
            approvedPayloadHash = "hash_aaa"
        )
        val request = ConfirmRequest(
            id = "req_1",
            title = "Approval needed",
            what = "Send data",
            why = "Test",
            risk = ConfirmRequest.Risk.MEDIUM,
            details = "details",
            timeoutSec = 0,
            taskId = "task_1",
            target = "https://example.com"
        )

        val approvalJob = async {
            controller.requestApproval(request, identity)
        }
        kotlinx.coroutines.yield()

        // Resolving with wrong task ID must be rejected
        val wrongIdResolved = controller.resolveConfirmation(ConfirmDecision.APPROVE_ONCE, taskId = "wrong_task", requestId = "req_1")
        assertFalse("Resolution with wrong taskId must return false", wrongIdResolved)

        // Resolving with correct IDs succeeds
        val correctResolved = controller.resolveConfirmation(ConfirmDecision.APPROVE_ONCE, taskId = "task_1", requestId = "req_1")
        assertTrue(correctResolved)

        val decision = approvalJob.await()
        assertEquals(ConfirmDecision.APPROVE_ONCE, decision)
    }

    @Test
    fun testChangedPayloadHashInvalidatesGrant() = runTest {
        val controller = ApprovalController()
        val identity1 = OperationIdentity(
            taskId = "task_1",
            actionId = "action_1",
            generation = 1L,
            destinationKey = "https://example.com",
            approvedPayloadHash = "hash_old"
        )
        val request = ConfirmRequest(
            id = "req_1",
            title = "Approval needed",
            what = "Send low-risk data",
            why = "Test",
            risk = ConfirmRequest.Risk.LOW,
            details = "details",
            timeoutSec = 0,
            taskId = "task_1",
            target = "https://example.com"
        )

        // Approve for task with hash_old
        val job1 = async { controller.requestApproval(request, identity1) }
        kotlinx.coroutines.yield()
        controller.resolveConfirmation(ConfirmDecision.APPROVE_FOR_TASK, "task_1", "req_1")
        val decision1 = job1.await()
        assertEquals(ConfirmDecision.APPROVE_FOR_TASK, decision1)

        // Now payload changes -> hash_new
        val identity2 = identity1.copy(approvedPayloadHash = "hash_new")
        val job2 = async { controller.requestApproval(request, identity2) }
        kotlinx.coroutines.yield()

        // Must NOT be auto-approved because payload hash changed!
        assertNotNull("Pending request must exist for modified payload", controller.confirmRequest.value)
        controller.resolveConfirmation(ConfirmDecision.DENY, "task_1", "req_1")
        val decision2 = job2.await()
        assertEquals(ConfirmDecision.DENY, decision2)
    }

    @Test
    fun clearingOneTaskKeepsAnotherTasksApprovalMemo() = runTest {
        val controller = ApprovalController()
        val requestA = ConfirmRequest(
            id = "req_a",
            title = "Approval needed",
            what = "Share summary",
            why = "A",
            risk = ConfirmRequest.Risk.LOW,
            details = "details",
            timeoutSec = 0,
            taskId = "task_a",
            target = "https://example.com/a"
        )
        val identityA = OperationIdentity(
            taskId = "task_a",
            actionId = "action_a",
            generation = 1L,
            destinationKey = "https://example.com/a",
            approvedPayloadHash = "hash_a"
        )
        val jobA = async { controller.requestApproval(requestA, identityA) }
        runCurrent()
        assertTrue(controller.resolveConfirmation(ConfirmDecision.APPROVE_FOR_TASK, "task_a", "req_a"))
        assertEquals(ConfirmDecision.APPROVE_FOR_TASK, jobA.await())

        val requestB = requestA.copy(id = "req_b", taskId = "task_b", target = "https://example.com/b")
        val identityB = OperationIdentity(
            taskId = "task_b",
            actionId = "action_b",
            generation = 1L,
            destinationKey = "https://example.com/b",
            approvedPayloadHash = "hash_b"
        )
        val jobB = async { controller.requestApproval(requestB, identityB) }
        runCurrent()
        assertTrue(controller.resolveConfirmation(ConfirmDecision.APPROVE_FOR_TASK, "task_b", "req_b"))
        assertEquals(ConfirmDecision.APPROVE_FOR_TASK, jobB.await())

        controller.clearForTask("task_a")
        val repeatedB = async { controller.requestApproval(requestB.copy(id = "req_b_again"), identityB) }
        runCurrent()
        assertTrue("Clearing task A must not revoke task B's memoized grant", repeatedB.isCompleted)
        assertEquals(ConfirmDecision.APPROVE_FOR_TASK, repeatedB.await())
    }

    @Test
    fun approvalWaitsIndefinitelyUntilTheUserAnswersOrStops() = runTest {
        val controller = ApprovalController()
        val identity = OperationIdentity(
            taskId = "task_wait",
            actionId = "action_wait",
            generation = 7L,
            destinationKey = "https://example.com",
            approvedPayloadHash = "payload-hash"
        )
        val request = ConfirmRequest(
            id = "req_wait",
            title = "Approval needed",
            what = "Send this message",
            why = "The action needs your approval",
            risk = ConfirmRequest.Risk.MEDIUM,
            details = "Message contents are bound to this request",
            timeoutSec = 0,
            taskId = identity.taskId,
            target = identity.destinationKey
        )

        val approvalJob = async { controller.requestApproval(request, identity) }
        runCurrent()
        advanceTimeBy(24 * 60 * 60 * 1000L)
        runCurrent()

        assertTrue("An unanswered approval must remain pending", approvalJob.isActive)
        assertNotNull(controller.confirmRequest.value)
        assertTrue(
            controller.resolveConfirmation(
                ConfirmDecision.APPROVE_ONCE,
                taskId = identity.taskId,
                requestId = request.id
            )
        )
        assertEquals(ConfirmDecision.APPROVE_ONCE, approvalJob.await())
    }

    @Test
    fun testWrongRequestIdDoesNotResolveAndRemainsPending() = runTest {
        val controller = ApprovalController()
        val identity = OperationIdentity(
            taskId = "task_1",
            actionId = "action_1",
            generation = 1L,
            destinationKey = "https://example.com"
        )
        val request = ConfirmRequest(
            id = "req_1",
            title = "Approval needed",
            what = "Send data",
            why = "Test",
            risk = ConfirmRequest.Risk.MEDIUM,
            details = "details",
            timeoutSec = 0,
            taskId = "task_1",
            target = "https://example.com"
        )

        val approvalJob = async { controller.requestApproval(request, identity) }
        kotlinx.coroutines.yield()

        val wrongReqResolved = controller.resolveConfirmation(ConfirmDecision.APPROVE_ONCE, taskId = "task_1", requestId = "wrong_req")
        assertFalse(wrongReqResolved)
        assertTrue(approvalJob.isActive)

        controller.resolveConfirmation(ConfirmDecision.APPROVE_ONCE, taskId = "task_1", requestId = "req_1")
        val decision = approvalJob.await()
        assertEquals(ConfirmDecision.APPROVE_ONCE, decision)
    }
}
