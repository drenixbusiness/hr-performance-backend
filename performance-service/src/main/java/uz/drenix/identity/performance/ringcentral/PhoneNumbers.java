package uz.drenix.identity.performance.ringcentral;

/**
 * Compares phone numbers the way people write them rather than the way they are stored.
 *
 * <p>The same extension can appear as {@code +13313291144} in the call log and as
 * {@code (331) 329-1144} in whatever an administrator typed into the user record. Matching on the
 * raw strings would silently attribute nobody's calls to anybody.
 */
public final class PhoneNumbers {

    private PhoneNumbers() {
    }

    /**
     * The last ten digits, which is what identifies a North American line regardless of how the
     * country code and punctuation were written. Shorter numbers — internal extensions — are kept
     * whole.
     */
    public static String key(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder digits = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        if (digits.isEmpty()) {
            return null;
        }
        return digits.length() > 10
                ? digits.substring(digits.length() - 10)
                : digits.toString();
    }
}
