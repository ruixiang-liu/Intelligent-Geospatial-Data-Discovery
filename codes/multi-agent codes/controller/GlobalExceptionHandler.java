package edu.psu.giscience.igdd.controller;

import edu.psu.giscience.igdd.exception.Neo4jConnectionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(Neo4jConnectionException.class)
    public ResponseEntity<Map<String, Object>> handleNeo4jConnectionException(Neo4jConnectionException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "status", "error",
                        "error", "Neo4j connection failed",
                        "message", e.getMessage(),
                        "stage", "connection_error"
                ));
    }
}
