package app.pickple.vote.service;

/** 투표 처리의 선행 검증 뒤 애플리케이션 내부 상태가 달라졌다. */
public class VoteConsistencyException extends RuntimeException {

    public VoteConsistencyException(String message) {
        super(message);
    }
}
