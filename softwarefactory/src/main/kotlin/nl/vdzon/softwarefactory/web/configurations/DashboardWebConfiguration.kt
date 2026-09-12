package nl.vdzon.softwarefactory.web.configurations

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.web.services.DashboardAuthInterceptor
import nl.vdzon.softwarefactory.web.services.DashboardAuthService
import nl.vdzon.softwarefactory.web.services.GoogleIdTokenVerifier
import nl.vdzon.softwarefactory.web.services.NimbusGoogleIdTokenVerifier
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Wiring van de dashboard-authenticatie: de Google-verifier tegen de geconfigureerde client-id en
 * de interceptor die `/api/v1/` en alles daaronder beschermt. Login zelf (`/api/v1/auth/`) en de bewust publieke
 * changelog (`/api/v1/public/`) blijven buiten de interceptor.
 */
@Configuration
class DashboardWebConfiguration(private val authService: DashboardAuthService) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(DashboardAuthInterceptor(authService))
            .addPathPatterns("/api/v1/**")
            .excludePathPatterns("/api/v1/auth/**", "/api/v1/public/**")
    }
}

@Configuration
class GoogleVerifierConfiguration {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Bean
    fun googleIdTokenVerifier(secrets: FactorySecrets): GoogleIdTokenVerifier {
        val clientId = secrets.googleClientId?.takeIf { it.isNotBlank() }
        if (clientId == null || secrets.dashboardRememberSecret.isNullOrBlank() || secrets.allowedEmails.isEmpty()) {
            logger.error(
                "Dashboard-login staat uit: SF_GOOGLE_CLIENT_ID, SF_ALLOWED_EMAILS en SF_DASHBOARD_REMEMBER_SECRET " +
                    "moeten alle drie gezet zijn.",
            )
        }
        return clientId?.let { NimbusGoogleIdTokenVerifier(it) }
            ?: GoogleIdTokenVerifier { throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Google-login is niet geconfigureerd") }
    }
}
