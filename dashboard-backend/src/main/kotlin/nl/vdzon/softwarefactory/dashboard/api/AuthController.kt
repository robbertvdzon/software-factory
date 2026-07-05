package nl.vdzon.softwarefactory.dashboard.api

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class AuthController(
    private val authService: AuthService,
) {
    /** Ruilt een Google ID-token in voor een eigen sessie-token (zie [AuthService.loginWithGoogle]). */
    @PostMapping("/api/v1/auth/google")
    fun google(@RequestBody request: GoogleLoginRequest): LoginResponse =
        authService.loginWithGoogle(request.idToken)
}
