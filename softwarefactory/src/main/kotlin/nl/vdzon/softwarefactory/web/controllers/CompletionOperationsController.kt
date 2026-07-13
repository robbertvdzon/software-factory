package nl.vdzon.softwarefactory.web.controllers

import jakarta.servlet.http.HttpServletRequest
import nl.vdzon.softwarefactory.config.ConfigApi
import nl.vdzon.softwarefactory.runtime.RuntimeApi
import nl.vdzon.softwarefactory.runtime.types.CompletionStep
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Authenticated break-glass operation; every accepted requeue is audited in Postgres. */
@RestController
@RequestMapping("/api/completions")
class CompletionOperationsController(
    private val runtimeApi: RuntimeApi,
    private val configApi: ConfigApi,
) {
    @PostMapping("/{completionId}/steps/{step}/requeue")
    fun requeue(
        @PathVariable completionId: Long,
        @PathVariable step: CompletionStep,
        @RequestBody body: RequeueRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Void> =
        if (!authorized(request)) {
            ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        } else {
            val operator = request.getHeader("X-Operator").orEmpty().ifBlank { "completion-api" }
            if (runtimeApi.requeueCompletion(completionId, step, operator, body.reason)) {
                ResponseEntity.accepted().build()
            } else {
                ResponseEntity.notFound().build()
            }
        }

    private fun authorized(request: HttpServletRequest): Boolean {
        val expected = configApi.resolvedValues()["SF_FACTORY_API_TOKEN"]?.takeIf(String::isNotBlank)
            ?: return false
        val authorization = request.getHeader("Authorization").orEmpty()
        val provided = authorization.removePrefix("Bearer ").takeIf { authorization.startsWith("Bearer ") }.orEmpty()
        return MessageDigest.isEqual(
            provided.toByteArray(StandardCharsets.UTF_8),
            expected.toByteArray(StandardCharsets.UTF_8),
        )
    }

    data class RequeueRequest(val reason: String)
}
