package com.pair.infra.ai;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 추론 서버(RunPod GPU) 경계 (계약 §3-2·§3-3). 백엔드는 응시자 사진 프레임을 여기로 넘기고, 세션을 시작·전환·종료하고, 상태를 확인한다.
 * 추론 서버는 {@link RunPodInferenceClient} 가 연결하고 inference-url 이 비어 있는 로컬 환경에서는 {@link StubInferenceClient} 를 쓴다.
 * 구현은 사진·키를 로그에 남기지 않는다. 판정 결과 중 이벤트는 이 경계로 돌아오지 않고 내부 콜백 API(`/internal/vision/events`)로 들어온다.
 * 세션 키(sessionKey)는 응시자의 불투명 visionKey 다.
 */
public interface InferenceClient {

    enum Channel {
        WEBCAM(1), PHONE(2);

        private final int code;

        Channel(int code) { this.code = code; }

        /** 추론 서버 프레임 헤더의 채널 번호 */
        public int code() { return code; }
    }

    enum Mode { CHECK, EXAM }

    enum StartResult { ACCEPTED, OVER_CAPACITY, DRAINING }

    /** 세션 등록 (멱등: 이미 있으면 보정·타이머 유지). 연결 오류는 예외 */
    StartResult start(String sessionKey, Mode mode, long startedAtMs, boolean recalibrate);

    /** 입장 점검에서 시험 모드로 전환. 추론 서버가 모르는 키면 false (호출자가 start 부터 다시 한다) */
    boolean changeMode(String sessionKey, long startedAtMs);

    /** 세션 종료 (멱등) */
    void end(String sessionKey);

    /** 프레임 1장. 응시자·채널마다 최신 한 장만 처리하며 응답을 기다리지 않는다. 실패하면 예외 */
    void sendFrame(String sessionKey, Channel channel, long seq, long recvMs, byte[] jpeg);

    /** 추론 서버가 새 프레임을 받을 수 있는지 (/readyz) */
    boolean ready();

    /** 추론 서버가 보내는 상태·판정·오류를 받을 곳 */
    void setListener(Listener listener);

    interface Listener {
        /** 활성 세션의 생존 신호 (추론 서버가 2초마다 보낸다) */
        void onState(String sessionKey);

        /** 입장 점검 판정 본문 (items·hints·holdMs·passed). 영상·사진은 없다 */
        void onCheck(String sessionKey, JsonNode body);

        /** 프레임 거절 (UNKNOWN_SESSION, OVER_CAPACITY, BAD_FRAME) */
        void onError(String sessionKey, String code);
    }
}
