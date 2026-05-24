package nl.vdzon.softwarefactory.preview.services

object PreviewTemplateRenderer {
    fun render(template: String?, prNumber: Int?): String? {
        if (template.isNullOrBlank() || prNumber == null || prNumber <= 0) {
            return null
        }
        return template.replace("{pr_num}", prNumber.toString())
    }
}
