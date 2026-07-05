package nl.vdzon.softwarefactory.dashboard.api

data class GoogleLoginRequest(val idToken: String = "")
data class LoginResponse(val token: String, val username: String)
