package uz.drenix.identity.performance.monday;

/** The board could not be read, and no earlier snapshot was available to fall back on. */
public class MondayUnavailableException extends uz.drenix.platform.security.UpstreamUnavailableException {

    public MondayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
