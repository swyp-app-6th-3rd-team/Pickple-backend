package app.pickple.auth.domain;

public enum Role {

    ROLE_USER,
    ROLE_ADMIN;

    public String authority() {
        return name();
    }
}
