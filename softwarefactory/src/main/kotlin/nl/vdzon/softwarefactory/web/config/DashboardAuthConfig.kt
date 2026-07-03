package nl.vdzon.softwarefactory.web.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import nl.vdzon.softwarefactory.web.services.FactoryDashboardAuth
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Login-poort voor de dashboard-routes: één [HandlerInterceptor] i.p.v. het per handler gekopieerde
 * `if (!auth.isAuthenticated(...)) return redirect(...)`-blok (voorheen ~15x in
 * FactoryDashboardController).
 *
 * Bewust een expliciete INCLUDE-lijst i.p.v. "alles behalve …": de web-module host naast de
 * dashboard-pagina's ook endpoints met eigen (of bewust géén) sessie-auth die hier nooit achter
 * mogen komen te liggen:
 * - `/login` / `/logout` — het loginproces zelf;
 * - `/events` — SSE met eigen afhandeling (geeft zonder login een lege, direct gesloten stream);
 * - `/my-actions/count` — geeft zonder login expliciet `"0"` terug (nav-badge), géén redirect;
 * - `/api/…` — FactoryApiController met eigen Bearer-token-auth;
 * - `/agent-knowledge` en `/agent-run/complete` — interne agent-callbacks zonder sessie;
 * - statics (`/static/…`, favicon) — publiek.
 */
@Configuration
class DashboardAuthConfig(
    private val dashboardAuthInterceptor: DashboardAuthInterceptor,
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(dashboardAuthInterceptor).addPathPatterns(PROTECTED_PATHS)
    }

    companion object {
        /** Alle dashboard-routes: de GET-pagina's én hun POST-acties (beide beschermd). */
        val PROTECTED_PATHS: List<String> = listOf(
            "/", "/dashboard",
            "/stories", "/stories/**",
            "/my-actions",
            "/projects", "/projects/**",
            "/nightly", "/nightly/**",
            "/agents", "/merged", "/downloads",
            "/settings", "/settings/**",
            "/admin/**",
        )
    }
}

/**
 * Stuurt niet-ingelogde requests op de dashboard-routes met `303 See Other` naar
 * `/login?next=…`, precies zoals de oude per-handler blokken deden.
 */
@Component
class DashboardAuthInterceptor(
    private val auth: FactoryDashboardAuth,
) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (auth.isAuthenticated(request, request.session)) {
            return true
        }
        val next = URLEncoder.encode(loginNextFor(request.method, request.requestURI), StandardCharsets.UTF_8)
        response.status = HttpStatus.SEE_OTHER.value()
        response.setHeader(HttpHeaders.LOCATION, "/login?next=$next")
        return false
    }

    companion object {
        /**
         * Waar de gebruiker na inloggen heen moet. Voor GET's: de opgevraagde pagina zelf. Voor
         * POST's: de pagina waar het formulier stond — een POST-pad is na login niet GET-baar,
         * en de oude per-handler blokken verwezen ook al naar de dragende pagina.
         */
        internal fun loginNextFor(method: String, path: String): String {
            if (!method.equals("POST", ignoreCase = true)) {
                return path
            }
            return when {
                path == "/stories/create" -> "/stories"
                // Story-acties (/stories/{key}/…) → terug naar de story-detailpagina.
                path.startsWith("/stories/") -> "/stories/" + path.removePrefix("/stories/").substringBefore('/')
                path.startsWith("/projects/") -> "/projects"
                path == "/nightly" || path.startsWith("/nightly/") -> "/nightly"
                path.startsWith("/settings") -> "/settings"
                // Restart/stop-knoppen staan op de settings-pagina.
                path.startsWith("/admin") -> "/settings"
                else -> path
            }
        }
    }
}
