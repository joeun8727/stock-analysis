package com.stockanalysis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling is on for live trading: {@code LiveScheduler} drives the trading-day state machine.
 * It does nothing unless the day has been explicitly armed, so an ordinary backtesting install is
 * unaffected.
 */
@SpringBootApplication
@EnableScheduling
public class StockAnalysisApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockAnalysisApplication.class, args);
    }
}
