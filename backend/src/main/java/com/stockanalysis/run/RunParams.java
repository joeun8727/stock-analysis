package com.stockanalysis.run;

/**
 * What was asked of a run, stored in {@code backtest_run.params}. {@code fromDate}/{@code toDate}
 * are the requested calendar bounds (null = the dataset's whole span); {@code barFromTs}/
 * {@code barToTs} are the first and last bar actually used, which is what the range resolved to.
 *
 * <p>Dates are ISO strings for the same reason {@link StoredResult} avoids {@code java.time} —
 * Hibernate's JSON mapper has no Java-time module.
 */
public record RunParams(
        String fromDate,
        String toDate,
        String barFromTs,
        String barToTs,
        int barCount
) {
}
