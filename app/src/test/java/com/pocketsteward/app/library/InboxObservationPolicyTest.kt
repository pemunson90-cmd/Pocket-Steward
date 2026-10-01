package com.pocketsteward.app.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InboxObservationPolicyTest {
    @Test fun firstNoticeCanRefreshImmediatelyAfterDebounce() {
        assertThat(InboxObservationPolicy.remainingDelay(0, null)).isEqualTo(0)
    }

    @Test fun repeatedNoticesWaitForTheMinimumInterval() {
        assertThat(InboxObservationPolicy.remainingDelay(20_000, 10_000)).isEqualTo(110_000)
        assertThat(InboxObservationPolicy.remainingDelay(130_000, 10_000)).isEqualTo(0)
        assertThat(InboxObservationPolicy.remainingDelay(500_000, 10_000)).isEqualTo(0)
    }

    @Test fun clockResetDoesNotSuspendObservationIndefinitely() {
        assertThat(InboxObservationPolicy.remainingDelay(0, 500_000)).isEqualTo(0)
    }

    @Test fun forcedObservedWorkStillRespectsTheBackgroundSwitch() {
        assertThat(InboxObservationPolicy.shouldRefresh(false, observed = true, forced = true)).isFalse()
        assertThat(InboxObservationPolicy.shouldRefresh(true, observed = true, forced = true)).isTrue()
    }

    @Test fun explicitManualRefreshRemainsAvailableWhenMonitoringIsOff() {
        assertThat(InboxObservationPolicy.shouldRefresh(false, observed = false, forced = true)).isTrue()
        assertThat(InboxObservationPolicy.shouldRefresh(false, observed = false, forced = false)).isFalse()
    }

    @Test fun watchScopeRequiresARealDescendantOfStorage() {
        val root = "/storage/emulated/0"
        assertThat(InboxObservationPolicy.isWithinStorage(root, "$root/Download")).isTrue()
        assertThat(InboxObservationPolicy.isWithinStorage("$root/", "$root/Download/Uncertain")).isTrue()
        assertThat(InboxObservationPolicy.isWithinStorage(root, root)).isFalse()
        assertThat(InboxObservationPolicy.isWithinStorage(root, "/storage/emulated/01/Download")).isFalse()
        assertThat(InboxObservationPolicy.isWithinStorage(root, "content://provider/Download")).isFalse()
        assertThat(InboxObservationPolicy.isWithinStorage("", "/data/private")).isFalse()
    }
}
