package nl.vdzon.softwarefactory.web.controllers

import nl.vdzon.softwarefactory.web.services.DashboardAuthService
import nl.vdzon.softwarefactory.web.services.DashboardLogin
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

data class GoogleLoginRequest(val idToken: String = "")

/** Login van het dashboard; bewust buiten de auth-interceptor (zie `DashboardWebConfiguration`). */
@RestController
class AuthController(private val authService: DashboardAuthService) {
    /** Ruilt een Google ID-token in voor een eigen sessietoken (zie [DashboardAuthService.loginWithGoogle]). */
    @PostMapping("/api/v1/auth/google")
    fun google(@RequestBody request: GoogleLoginRequest): DashboardLogin = authService.loginWithGoogle(request.idToken)
}
