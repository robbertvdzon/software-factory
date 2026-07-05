package nl.vdzon.softwarefactory.core

/**
 * Poort voor het uitlezen van de deploy-status van een Kubernetes/OpenShift-deployment.
 * Leeft in core zodat de pipeline (DeploySubtaskHandler) geen kennis heeft van HOE de
 * status wordt opgehaald; de ProcessBuilder/kubectl-implementatie zit als adapter in
 * het runtime-package. Tests kunnen zo een fake injecteren i.p.v. een echt kubectl-proces.
 */
fun interface DeploymentStatusProbe {
    /**
     * De huidige container-image van de deployment, of `null` wanneer de status niet
     * opvraagbaar is (kubectl-fout, timeout, e.d.). Een lege string betekent: de call
     * slaagde, maar er is (nog) geen image bekend.
     */
    fun currentImage(namespace: String, deployment: String): String?

    /**
     * De status van de ArgoCD `Application`-CR [application] in [namespace], of `null` wanneer die
     * niet opvraagbaar is (kubectl-fout, CR onbekend, timeout). Default `null` zodat bestaande
     * SAM-implementaties/tests blijven werken; alleen de kubectl-adapter vult 'm daadwerkelijk.
     */
    fun argoApplicationStatus(namespace: String, application: String): ArgoApplicationStatus? = null
}

/**
 * De relevante deelvelden van een ArgoCD `Application`-CR-status. Een geslaagde GitOps-deploy geldt
 * pas als `syncStatus=Synced` én `healthStatus=Healthy` én `operationPhase=Succeeded` op de verwachte
 * [revision]. Lege strings betekenen: veld (nog) niet aanwezig in de CR.
 */
data class ArgoApplicationStatus(
    val syncStatus: String,
    val healthStatus: String,
    val operationPhase: String,
    val revision: String,
)
