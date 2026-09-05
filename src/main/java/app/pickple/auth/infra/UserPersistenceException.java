package app.pickple.auth.infra;

/** 사용자 영속화 과정에서만 성립해야 하는 내부 상태가 깨졌다. */
public class UserPersistenceException extends RuntimeException {

    public UserPersistenceException(String message) {
        super(message);
    }
}
