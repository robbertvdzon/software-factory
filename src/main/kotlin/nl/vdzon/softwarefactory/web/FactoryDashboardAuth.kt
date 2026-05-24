package nl.vdzon.softwarefactory.web

import jakarta.servlet.http.HttpSession
import nl.vdzon.softwarefactory.config.FactoryEnvironmentProvider
import org.springframework.stereotype.Service

@Service
class FactoryDashboardAuth(
    environmentProvider: FactoryEnvironmentProvider,
) {
    val username: String = environmentProvider.resolvedValues()["SF_DASHBOARD_USERNAME"]?.takeIf { it.isNotBlank() } ?: "admin"
    private val password: String = environmentProvider.resolvedValues()["SF_DASHBOARD_PASSWORD"]?.takeIf { it.isNotBlank() } ?: "admin"

    fun isAuthenticated(session: HttpSession): Boolean =
        session.getAttribute(SESSION_USER) == username

    fun login(session: HttpSession, username: String, password: String): Boolean {
        if (username == this.username && password == this.password) {
            session.setAttribute(SESSION_USER, username)
            return true
        }
        return false
    }

    fun logout(session: HttpSession) {
        session.invalidate()
    }

    companion object {
        private const val SESSION_USER = "software-factory-user"
    }
}
