package uz.drenix.edge.web;

/**
 * A rejection with a message written for whoever made the request.
 *
 * <p>{@link IllegalArgumentException} on its own answers <em>"Request is not valid."</em> and
 * nothing more, deliberately: it is thrown by every library in the stack and its message can quote
 * internal types, field names and input the caller never sent. Passing those through would be a
 * slow leak of how this service is built.
 *
 * <p>The cost of that caution was that a carefully written explanation — "month must look like
 * 2026-08", "reason is required: send ?reason=..." — reached the caller as the same four generic
 * words as a parser failure. Somebody integrating against this had no way to tell which of five
 * query parameters they had got wrong.
 *
 * <p>So this type marks the difference. Throwing it is a statement that the message was written to
 * be read by the caller and contains nothing that is not already theirs. Plain
 * {@code IllegalArgumentException} keeps the generic answer.
 */
public class BadRequestException extends IllegalArgumentException {

    public BadRequestException(String message) {
        super(message);
    }
}
