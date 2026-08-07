---
default_base_branch: main
branch_prefix: ai/
preview_url_template: ""
preview_namespace_template: ""
preview_db_secret_recipe: ""
---

# Deployment

Beschrijf hoe deze applicatie gedeployd wordt, hoe preview-omgevingen ontstaan,
en welke URL's of namespaces testers moeten gebruiken.

Vul de previewvelden pas in wanneer het project daadwerkelijk een preview-deploy heeft. Bijvoorbeeld:

```yaml
preview_url_template: "https://app-pr-{pr_num}.example.com"
preview_namespace_template: "app-pr-{pr_num}"
```
