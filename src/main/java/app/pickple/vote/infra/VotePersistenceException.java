package app.pickple.vote.infra;

/** 투표 영속화 과정에서만 성립해야 하는 내부 상태가 깨졌다. */
public class VotePersistenceException extends RuntimeException {

    public VotePersistenceException(String message) {
        super(message);
    }
}
