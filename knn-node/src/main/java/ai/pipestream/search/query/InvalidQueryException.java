package ai.pipestream.search.query;

/**
 * Thrown when a v1alpha1 Query AST cannot be compiled against a collection
 * schema: unknown fields, type mismatches, malformed clauses, or constructs
 * the compiler does not support. Maps naturally to gRPC INVALID_ARGUMENT.
 */
public class InvalidQueryException extends IllegalArgumentException {

    public InvalidQueryException(String message) {
        super(message);
    }

    public InvalidQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
