package nl.vdzon.softwarefactory.dashboard.api

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class AuthController(
    private val authService: AuthService,
) {
    @PostMapping("/api/v1/auth/login")
    fun login(@RequestBody request: LoginRequest): LoginResponse =
        authService.login(request.username, request.password)
}
