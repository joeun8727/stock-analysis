package com.stockanalysis.live.broker;

/**
 * A call to the broker failed. Extends {@link IllegalStateException} so the existing
 * {@code ApiExceptionHandler} turns it into a 500 with the message intact — these are operational
 * failures (bad credentials, rejected order, API change), not user input errors.
 */
public class KisApiException extends IllegalStateException {

    public KisApiException(String message) {
        super(message);
    }

    public KisApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
