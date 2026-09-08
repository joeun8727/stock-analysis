package com.stockanalysis.live.broker;

import com.stockanalysis.backtest.Bar;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 실투자가 브로커에게 필요로 하는 것 전부. 일부러 좁게 유지합니다: 매매 논리 자체는 공유되는
 * {@code StrategySpec} 평가기에 있고, 이 인터페이스는 시세를 들여오고 주문을 내보낼 뿐입니다.
 *
 * <p>구현이 둘 있습니다 — {@link KisBrokerClient}는 한국투자증권과 통신하고,
 * {@link DryRunBroker}는 아무것도 보내지 않고 무엇을 주문했을지만 기록합니다.
 */
public interface BrokerClient {

    /**
     * 관측된 가격 하나. {@code ts}는 여기 있는 모든 타임스탬프와 마찬가지로 KST 벽시계입니다.
     * {@code cumulativeVolume}은 원본이 알려줄 때의 세션 누적 거래량입니다(아니면 null) —
     * 봉 거래량은 이 값 둘의 차이입니다. 봉별 거래량을 직접 주는 피드가 없기 때문입니다.
     */
    record Quote(String ticker, double price, LocalDateTime ts, boolean estimated,
                 Double cumulativeVolume) {

        public Quote(String ticker, double price, LocalDateTime ts, boolean estimated) {
            this(ticker, price, ts, estimated, null);
        }
    }

    record OrderRequest(String ticker, boolean buy, long quantity, String orderType,
                        Double limitPrice, String clientOrderId) {
    }

    /**
     * 주문이 접수됐을 때 브로커가 한 말. 체결을 뜻하지 <em>않습니다</em> — 체결은
     * {@link #orderStatus}에서 옵니다. 시장가 주문도 거부되거나 일부만 체결될 수 있기 때문입니다.
     */
    record OrderAck(String brokerOrderNo, boolean accepted, String message, String raw) {
    }

    record OrderFill(String brokerOrderNo, long filledQuantity, Double filledPrice,
                     boolean done, boolean rejected, String raw) {
    }

    /** 계좌의 보유 종목 하나. */
    record Holding(String ticker, long quantity, double avgPrice) {
    }

    record Balance(double cashAvailable, List<Holding> holdings, String raw) {

        public long quantityOf(String ticker) {
            return holdings.stream()
                    .filter(h -> h.ticker().equals(ticker))
                    .mapToLong(Holding::quantity)
                    .sum();
        }
    }

    /** 이 클라이언트가 매매하는 계좌의 사람이 읽을 이름. 화면과 로그용입니다. */
    String describe();

    /**
     * 국내 선물의 현재가. 장전 동시호가 구간에서는 체결이 아니라 예상체결가입니다 —
     * 어느 쪽인지는 {@link Quote#estimated()}가 알려주며, 장전 판단은 여기서 움직이는 값을
     * 받아오는 데 달려 있습니다.
     */
    Quote futuresQuote(String ticker);

    /** 상장 주식이나 ETF의 현재가. */
    Quote stockQuote(String ticker);

    /**
     * 지정한 날짜의 주식/ETF 분봉. 오래된 것부터이며 이동평균은 {@code NaN}으로 둡니다 —
     * 호출자가 계산해야 백테스트의 정의와 정확히 맞기 때문입니다. 세션 시작 전 지표 워밍업에
     * 씁니다.
     */
    List<Bar> minuteBars(String ticker, LocalDate date);

    OrderAck placeOrder(OrderRequest request);

    OrderFill orderStatus(String brokerOrderNo, String ticker);

    Balance balance();
}
