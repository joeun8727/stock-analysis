package com.stockanalysis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 실투자를 위해 스케줄링을 켭니다: {@code LiveScheduler}가 거래일 상태 기계를 돌립니다.
 * 그날을 명시적으로 활성화하지 않으면 아무것도 하지 않으므로, 백테스트만 쓰는 설치에는
 * 영향이 없습니다.
 */
@SpringBootApplication
@EnableScheduling
public class StockAnalysisApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockAnalysisApplication.class, args);
    }
}
