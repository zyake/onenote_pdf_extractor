package com.extractor.util;

import java.util.concurrent.Callable;

/**
 * Utility for executing operations with configurable retry and exponential backoff.
 * Used by GraphCredentialsAuthModule, S3PdfStore, and NotebookLmUploader.
 */
public final class RetryExecutor {

    private RetryExecutor() {
        // static utility — no instantiation
    }

    /**
     * Executes the given operation, retrying up to {@code maxRetries} times on failure
     * with exponential backoff ({@code initialBackoffMs * 2^attempt}).
     *
     * @param operation        the operation to execute
     * @param maxRetries       maximum number of retry attempts (0 means no retries)
     * @param initialBackoffMs initial backoff delay in milliseconds before the first retry
     * @param <T>              the return type of the operation
     * @return the result of the first successful invocation
     * @throws Exception the last exception if all attempts are exhausted
     */
    public static <T> T execute(Callable<T> operation, int maxRetries, long initialBackoffMs) throws Exception {
        Exception lastException = null;

        for (var attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return operation.call();
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxRetries) {
                    var backoffMs = initialBackoffMs * (1L << attempt);
                    Thread.sleep(backoffMs);
                }
            }
        }

        throw lastException;
    }
}
