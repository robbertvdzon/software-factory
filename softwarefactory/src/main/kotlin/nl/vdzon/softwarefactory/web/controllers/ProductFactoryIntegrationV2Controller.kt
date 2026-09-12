package nl.vdzon.softwarefactory.web.controllers

import nl.vdzon.softwarefactory.web.services.ProductFactoryIntegrationV2Service
import nl.vdzon.softwarefactory.web.services.ProductFactoryV2CancelRequest
import nl.vdzon.softwarefactory.web.services.ProductFactoryV2ErrorResponse
import nl.vdzon.softwarefactory.web.services.ProductFactoryV2Exception
import nl.vdzon.softwarefactory.web.services.ProductFactoryV2StoryRequest
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Product Factory-integratie v2: idempotente storyaanmaak met bijlagen, statusquery en annulering.
 * De HTTP-laag is dun; contract, validatie en idempotentie zitten in [ProductFactoryIntegrationV2Service].
 */
@RestController
@RequestMapping("/api/integrations/v2")
class ProductFactoryIntegrationV2Controller(
    private val service: ProductFactoryIntegrationV2Service,
) {
    @GetMapping("/status")
    fun status(@RequestHeader("Authorization", required = false) authorization: String?) =
        service.status(authorization)

    @PostMapping("/stories")
    fun createStory(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody body: ProductFactoryV2StoryRequest,
    ) = service.createStory(authorization, idempotencyKey, body)

    @GetMapping("/stories/{storyKey}")
    fun story(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable storyKey: String,
    ) = service.story(authorization, storyKey)

    @GetMapping("/stories")
    fun stories(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestParam(required = false) productId: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) idempotencyKey: String?,
    ) = service.stories(authorization, productId, status, idempotencyKey)

    @PostMapping("/stories/{storyKey}/cancel")
    fun cancelStory(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable storyKey: String,
        @RequestBody body: ProductFactoryV2CancelRequest,
    ) = service.cancelStory(authorization, storyKey, body)
}

@RestControllerAdvice(assignableTypes = [ProductFactoryIntegrationV2Controller::class])
class ProductFactoryIntegrationV2ErrorHandler {
    @ExceptionHandler(ProductFactoryV2Exception::class)
    fun productFactoryError(exception: ProductFactoryV2Exception): ResponseEntity<ProductFactoryV2ErrorResponse> =
        ResponseEntity.status(exception.status).body(ProductFactoryV2ErrorResponse(exception.code, exception.message, exception.retryable))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadableRequest(): ResponseEntity<ProductFactoryV2ErrorResponse> =
        ResponseEntity.badRequest().body(ProductFactoryV2ErrorResponse("INVALID_REQUEST", "Ongeldige JSON-requestbody.", false))
}
