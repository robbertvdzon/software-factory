package nl.vdzon.softwarefactory.web.services

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.servlet.HandlerInterceptor

/**
 * Bewaakt alle dashboard-endpoints onder `/api/v1/` en alles daaronder (zie `DashboardWebConfiguration` voor de
 * uitzonderingen: login en de publieke changelog). Een geldig sessietoken zet het e-mailadres van
 * de ingelogde gebruiker als request-attribuut [USER_ATTRIBUTE]; anders stopt de aanroep met 401.
 * Fail-closed: een nieuw endpoint onder `/api/v1` is automatisch beschermd.
 */
class DashboardAuthInterceptor(private val auth: DashboardAuthService) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val email = auth.requireAuthorization(request.getHeader("Authorization"))
        request.setAttribute(USER_ATTRIBUTE, email)
        return true
    }

    companion object {
        const val USER_ATTRIBUTE = "dashboardUser"
    }
}
