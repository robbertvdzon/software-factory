package nl.vdzon.softwarefactory.web

import nl.vdzon.softwarefactory.dashboard.models.AgentExecutionConfigSaveInput
import nl.vdzon.softwarefactory.web.controllers.DashboardApiErrorHandler
import nl.vdzon.softwarefactory.web.controllers.DashboardCommandController
import nl.vdzon.softwarefactory.web.services.DashboardAuthInterceptor
import nl.vdzon.softwarefactory.web.services.DashboardAuthService
import nl.vdzon.softwarefactory.web.services.GoogleIdTokenVerifier
import nl.vdzon.softwarefactory.web.services.GoogleIdentity
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import kotlin.test.Test
import kotlin.test.assertEquals

/** Wiring die de fixture met echte services niet kan dekken: de ingelogde gebruiker als `updatedBy`. */
class DashboardCommandControllerTest {
    private val commands = object : StubDashboardCommands() {
        var saved: AgentExecutionConfigSaveInput? = null
        override fun saveAgentExecutionConfig(input: AgentExecutionConfigSaveInput) { saved = input }
    }
    private val authService = DashboardAuthService(
        DashboardApiFixtures.fakeSecrets(),
        GoogleIdTokenVerifier { GoogleIdentity(DashboardApiFixtures.USER, emailVerified = true) },
    )
    private val token = authService.loginWithGoogle("stub").token
    private val mvc = MockMvcBuilders.standaloneSetup(DashboardCommandController(commands, StubFactoryOperations()))
        .addMappedInterceptors(arrayOf("/api/v1/**"), DashboardAuthInterceptor(authService))
        .setControllerAdvice(DashboardApiErrorHandler())
        .build()

    @Test
    fun `agent-execution slaat de keuze op met de ingelogde gebruiker als updatedBy`() {
        mvc.perform(
            post("/api/v1/settings/agent-execution")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"role":"developer","projectKey":"softwarefactory","vendorId":"codex","model":"gpt-5.6-sol","mode":"HIGH"}"""),
        ).andExpect(status().isOk)

        val saved = commands.saved!!
        assertEquals("developer", saved.role)
        assertEquals("softwarefactory", saved.projectKey)
        assertEquals("codex", saved.vendorId)
        assertEquals("gpt-5.6-sol", saved.model)
        assertEquals("HIGH", saved.mode)
        assertEquals(DashboardApiFixtures.USER, saved.updatedBy)
    }

    @Test
    fun `agent-execution zonder projectKey slaat een platformbrede keuze op`() {
        mvc.perform(
            post("/api/v1/settings/agent-execution")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"role":"developer","projectKey":"","vendorId":"codex","model":"gpt-5.6-sol","mode":"HIGH"}"""),
        ).andExpect(status().isOk)

        assertEquals(null, commands.saved!!.projectKey)
    }
}
