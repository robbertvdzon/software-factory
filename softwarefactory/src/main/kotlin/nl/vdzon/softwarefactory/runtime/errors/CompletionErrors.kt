package nl.vdzon.softwarefactory.runtime.errors

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

@ResponseStatus(HttpStatus.CONFLICT)
class CompletionPayloadConflictException(message: String) : RuntimeException(message)

@ResponseStatus(HttpStatus.BAD_REQUEST)
class CompletionPayloadRejectedException(message: String) : RuntimeException(message)

@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
class CompletionPermanentlyFailedException(message: String) : RuntimeException(message)
