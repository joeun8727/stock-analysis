package com.stockanalysis.live.broker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockanalysis.domain.LiveMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the broker for the configured mode. Which implementation is in play is decided once, here,
 * from an environment variable — nothing at runtime can promote a dry run into a real order.
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
