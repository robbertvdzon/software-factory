---
default_base_branch: main
branch_prefix: ai/
preview_url_template: ""
preview_namespace_template: ""
---

# Deployment

De factory zelf draait lokaal op de laptop van de gebruiker:

```bash
mvn spring-boot:run
```

Later kan dit ook via een jar:

```bash
mvn package
java -jar target/softwarefactory-0.0.1-SNAPSHOT.jar
```

Er is voor deze repo nog geen preview-deploy ingericht. De tester-flow die
OpenShift preview namespaces gebruikt, is vooral bedoeld voor target-apps die
door de factory gebouwd worden.

Start de applicatie vanuit de root van de repo, zodat `./secrets.env` gevonden
wordt. Voor afwijkende lokale runs kan `SF_SECRETS_FILE` naar een ander bestand
wijzen.
