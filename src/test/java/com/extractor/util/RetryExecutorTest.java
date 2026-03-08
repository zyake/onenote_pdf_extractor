package com.extractor.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for RetryExecutor.
 * Validates: Requirements 2.3, 4.5, 5.3
 */
class RetryExecutorTest {

    @Test
    void returnsImmediatelyOnFirstSuccess() throws Exception {
        var result = RetryExecutor.execute(() -> "ok", 3, 1);

        assertThat(result).isEqualTo("ok");
    }

    @Test
    void retriesAndReturnsOnEventualSuccess() throws Exception {
        var attempts = new AtomicInteger(0);
        Callable<String> failTwiceThenSucceed = () -> {
            if (attempts.getAndIncrement() < 2) {
                throw new RuntimeException("transient failure");
            }
            return "recovered";
        };

        var result = RetryExecutor.execute(failTwiceThenSucceed, 3, 1);

        assertThat(result).isEqualTo("recovered");
        assertThat(attempts.get()).isEqualTo(3); // 2 failures + 1 success
    }

    @Test
    void throwsLastExceptionAfterExhaustingRetries() {
        var attempts = new AtomicInteger(0);
        Callable<String> alwaysFails = () -> {
            var n = attempts.incrementAndGet();
            throw new RuntimeException("failure #" + n);
        };

        assertThatThrownBy(() -> RetryExecutor.execute(alwaysFails, 2, 1))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("failure #3"); // initial + 2 retries = 3 attempts
    }

    @Test
    void zeroRetriesMeansNoRetry() {
        var attempts = new AtomicInteger(0);
        Callable<String> alwaysFails = () -> {
            attempts.incrementAndGet();
            throw new RuntimeException("fail");
        };

        assertThatThrownBy(() -> RetryExecutor.execute(alwaysFails, 0, 1))
                .isInstanceOf(RuntimeException.class);
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void executesExactlyMaxRetriesPlusOneAttempts() throws Exception {
        var attempts = new AtomicInteger(0);
        Callable<String> failsExactlyMaxRetries = () -> {
            var n = attempts.getAndIncrement();
            if (n < 3) {
                throw new RuntimeException("attempt " + n);
            }
            return "success on last try";
        };

        var result = RetryExecutor.execute(failsExactlyMaxRetries, 3, 1);

        assertThat(result).isEqualTo("success on last try");
        assertThat(attempts.get()).isEqualTo(4); // 3 failures + 1 success
    }

    @Test
    void propagatesCheckedExceptions() {
        Callable<String> throwsChecked = () -> {
            throw new java.io.IOException("disk error");
        };

        assertThatThrownBy(() -> RetryExecutor.execute(throwsChecked, 1, 1))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("disk error");
    }

    @Test
    void appliesExponentialBackoff() throws Exception {
        var attempts = new AtomicInteger(0);
        var timestamps = new long[3];

        Callable<String> trackTiming = () -> {
            var n = attempts.getAndIncrement();
            timestamps[n] = System.nanoTime();
            if (n < 2) {
                throw new RuntimeException("retry");
            }
            return "done";
        };

        RetryExecutor.execute(trackTiming, 2, 50);

        // First backoff ~50ms, second backoff ~100ms
        var firstGapMs = (timestamps[1] - timestamps[0]) / 1_000_000;
        var secondGapMs = (timestamps[2] - timestamps[1]) / 1_000_000;

        assertThat(firstGapMs).isGreaterThanOrEqualTo(30);  // ~50ms with tolerance
        assertThat(secondGapMs).isGreaterThanOrEqualTo(70);  // ~100ms with tolerance
        assertThat(secondGapMs).isGreaterThan(firstGapMs);
    }
}
