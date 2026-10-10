package dev.valart.postgreslab.api;

import dev.valart.postgreslab.service.OfferService;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badSetting(IllegalArgumentException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail integrity(DataIntegrityViolationException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Database constraint rejected the request: check names, references, and non-negative prices.");
    }

    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail database(DataAccessException exception) {
        boolean deadlock = OfferService.isDeadlock(exception);
        return ProblemDetail.forStatusAndDetail(deadlock ? HttpStatus.CONFLICT : HttpStatus.SERVICE_UNAVAILABLE,
                deadlock ? "Transaction aborted by PostgreSQL (SQLSTATE 40P01)" : "Database operation failed");
    }
}
