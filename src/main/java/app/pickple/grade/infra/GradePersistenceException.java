package app.pickple.grade.infra;

/** 등급 영속화 과정에서만 성립해야 하는 내부 상태가 깨졌다 (ADR-0039). */
public class GradePersistenceException extends RuntimeException {

    public GradePersistenceException(String message) {
        super(message);
    }
}
