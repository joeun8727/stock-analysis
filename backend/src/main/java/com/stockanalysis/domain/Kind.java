package com.stockanalysis.domain;

/** Role of a dataset. ETF datasets come in LEVERAGE/INVERSE pairs; others are SINGLE. */
public enum Kind {
    LEVERAGE,
    INVERSE,
    SINGLE
}
