package com.stockanalysis.live.broker;

/**
 * 브로커 호출이 실패했습니다. {@link IllegalStateException}을 상속해서 기존
 * {@code ApiExceptionHandler}가 메시지를 살린 채 500으로 내보내게 합니다 — 이건 사용자 입력
 * 오류가 아니라 운영상의 실패(잘못된 자격증명, 거부된 주문, API 변경)이기 때문입니다.
 */
public class KisApiException extends IllegalStateException {

    public KisApiException(String message) {
        super(message);
    }

    public KisApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
