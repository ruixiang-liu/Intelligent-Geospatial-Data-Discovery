package edu.psu.giscience.igdd.exception;

/**
 * Exception thrown when Neo4j connection fails or is unavailable.
 */
public class Neo4jConnectionException extends RuntimeException {
    public Neo4jConnectionException(String message) {
        super(message);
    }

    public Neo4jConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
