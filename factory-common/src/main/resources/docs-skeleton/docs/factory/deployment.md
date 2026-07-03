---
default_base_branch: main
branch_prefix: ai/
preview_url_template: "https://example-pr-{pr_num}.example.com"
preview_namespace_template: "example-pr-{pr_num}"
preview_db_secret_recipe: |
  echo "Vul hier optioneel het commando in om de preview database secret op te halen."
---

# Deployment

Beschrijf hoe deze applicatie gedeployd wordt, hoe preview-omgevingen ontstaan,
en welke URL's of namespaces testers moeten gebruiken.
