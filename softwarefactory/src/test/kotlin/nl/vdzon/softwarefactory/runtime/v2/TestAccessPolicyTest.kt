package nl.vdzon.softwarefactory.runtime.v2

import nl.vdzon.softwarefactory.core.AgentRole
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.*

class TestAccessPolicyTest {
    @Test
    fun `alleen de tester van de toegewezen repository ontvangt een nietproductietoken`() {
        val grants = TestAccessPolicy.parse("robbertvdzon/hkh=HKH__PREVIEW_AGENT_TOKEN")
        assertThat(TestAccessPolicy.selected(AgentRole.TESTER, "https://github.com/robbertvdzon/hkh.git", grants)).containsExactly("HKH__PREVIEW_AGENT_TOKEN")
        assertThat(TestAccessPolicy.selected(AgentRole.TESTER, "robbertvdzon/pvdd", grants)).isEmpty()
        assertThat(TestAccessPolicy.selected(AgentRole.DEVELOPER, "robbertvdzon/hkh", grants)).isEmpty()
    }
    @Test
    fun `productie en beheercredentials worden geweigerd`() {
        for (key in listOf("HKH__PRODUCTION_AGENT_TOKEN", "HKH__KUBECONFIG", "HKH__TEST_SIGNING_SECRET")) {
            assertThatIllegalArgumentException().isThrownBy { TestAccessPolicy.parse("robbertvdzon/hkh=$key") }
        }
    }
}
