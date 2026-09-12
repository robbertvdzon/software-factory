package nl.vdzon.softwarefactory.web.controllers

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.beans.TypeMismatchException
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

/** Foutbody van de dashboard-API; de frontend toont [message] en schakelt op [code]. */
data class DashboardApiError(val code: String, val message: String)

/** Opgevraagde story, attachment of run bestaat niet (HTTP 404). */
class DashboardNotFoundException(message: String) : RuntimeException(message)

/** Een attachment met deze naam bestaat al met andere inhoud (HTTP 409). */
class AttachmentConflictException(message: String) : RuntimeException(message)

/**
 * Vertaalt fouten uit de dashboard-controllers naar een vaste JSON-vorm. Een
 * [IllegalArgumentException] uit een service is een clientfout (400): een retry met dezelfde
 * invoer faalt opnieuw. Alles wat onverwacht is wordt een 500 met de melding, zodat de frontend
 * die kan tonen.
 */
// DashboardStatusController hoort hier bewust niet bij: het SSE-kanaal meldt zijn time-out via
// AsyncRequestTimeoutException nadat de response al is gecommit, en dat mag geen foutbody worden.
@RestControllerAdvice(assignableTypes = [
    DashboardQueryController::class,
    DashboardCommandController::class,
    ProductFactoryIntegrationController::class,
])
class DashboardApiErrorHandler {
    @ExceptionHandler(DashboardNotFoundException::class)
    fun notFound(exception: DashboardNotFoundException) = error(HttpStatus.NOT_FOUND, "NOT_FOUND", exception)

    @ExceptionHandler(AttachmentConflictException::class)
    fun conflict(exception: AttachmentConflictException) = error(HttpStatus.CONFLICT, "CONFLICT", exception)

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalidParams(exception: IllegalArgumentException) = error(HttpStatus.BAD_REQUEST, "INVALID_PARAMS", exception)

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(exception: HttpMessageNotReadableException): ResponseEntity<DashboardApiError> =
        ResponseEntity.badRequest().body(DashboardApiError("INVALID_PARAMS", "Ongeldige JSON-requestbody."))

    /** Een niet-numerieke id in het pad of een verkeerd getypte queryparameter is een clientfout. */
    @ExceptionHandler(TypeMismatchException::class)
    fun typeMismatch(exception: TypeMismatchException): ResponseEntity<DashboardApiError> =
        ResponseEntity.badRequest().body(DashboardApiError("INVALID_PARAMS", exception.message ?: "Ongeldige parameter."))

    @ExceptionHandler(ResponseStatusException::class)
    fun status(exception: ResponseStatusException): ResponseEntity<DashboardApiError> =
        ResponseEntity.status(exception.statusCode)
            .body(DashboardApiError(exception.statusCode.toString(), exception.reason ?: exception.message))

    /**
     * Springs eigen request-fouten (ontbrekende parameter, verkeerde methode, geen body) dragen hun
     * status al bij zich; die mag de generieke 500 hieronder niet overschrijven.
     */
    @ExceptionHandler(Exception::class)
    fun internal(exception: Exception): ResponseEntity<DashboardApiError> =
        if (exception is ErrorResponse) {
            ResponseEntity.status(exception.statusCode)
                .body(DashboardApiError(exception.statusCode.toString(), exception.body.detail ?: exception.message ?: "Ongeldig verzoek"))
        } else {
            error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", exception)
        }

    private fun error(status: HttpStatus, code: String, exception: Exception): ResponseEntity<DashboardApiError> =
        ResponseEntity.status(status).body(DashboardApiError(code, exception.message ?: "Onbekende fout"))
}
