package uz.drenix.identity.performance.ringcentral;

/** RingCentral could not be reached, or refused the credentials. */
public class RingCentralUnavailableException extends uz.drenix.platform.security.UpstreamUnavailableException {

    public RingCentralUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
