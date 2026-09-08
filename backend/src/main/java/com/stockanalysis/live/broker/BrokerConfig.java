package com.stockanalysis.live.broker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockanalysis.domain.LiveMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 설정된 모드에 맞는 브로커를 연결합니다. 어느 구현이 쓰일지는 여기서 환경변수로 딱 한 번
 * 정해집니다 — 실행 중에 드라이런이 진짜 주문으로 승격될 수 있는 경로가 없습니다.
 */
@Configuration
@EnableConfigurationProperties(KisProperties.class)
public class BrokerConfig {

    private static final Logger log = LoggerFactory.getLogger(BrokerConfig.class);

    @Bean
    public BrokerClient brokerClient(KisProperties props, KisTokenStore tokens, ObjectMapper mapper) {
        KisBrokerClient kis = new KisBrokerClient(props, tokens, mapper);
        if (props.getMode() == LiveMode.DRY_RUN) {
            log.info("실투자 모드: DRY_RUN — 시세만 실제로 조회하고 주문은 보내지 않습니다.");
            return new DryRunBroker(kis);
        }
        if (props.getMode() == LiveMode.REAL) {
            log.warn("실투자 모드: REAL — 실계좌에 진짜 주문이 나갑니다. 계좌 {}", kis.describe());
        } else {
            log.info("실투자 모드: PAPER — 모의투자 서버로 주문합니다. 계좌 {}", kis.describe());
        }
        return kis;
    }
}
