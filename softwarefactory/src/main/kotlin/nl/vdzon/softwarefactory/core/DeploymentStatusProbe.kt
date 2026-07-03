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
}
