package fi.monopoly.server.session;

/**
 * Thrown by {@link SessionRegistry} when a new session cannot be created because either
 * the maximum concurrent session count or the CPU load threshold has been exceeded.
 */
public final class SessionLimitExceededException extends RuntimeException {

    private final String errorCode;

    public SessionLimitExceededException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
