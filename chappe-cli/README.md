# chappe-cli

Launcher CLI standalone pour Chappe — sert un répertoire statique en HTTP/1.1+H2
sans launcher Java applicatif. Configuration via mini-YAML in-house ou flags CLI.

> Zéro dépendance hors JDK. Tout le parsing YAML, le merge config, la négociation
> de compression et le packaging tient dans ~600 LOC du module `chappe-cli`.

## Build

Depuis la racine du sous-projet `chappe/` :

```bash
sdk env                                # Java 25 + Maven 4.0.0-rc-5
mvn -ntp -pl chappe-cli -am package    # produit le fat jar
```

Artefacts générés dans `chappe-cli/target/` :

| Fichier | Description |
|---|---|
| `chappe-cli-{version}.jar` | jar simple (module-info, requiert chappe-api/core sur le module path) |
| `chappe-cli-{version}-shaded.jar` | fat jar autonome (toutes deps fusionnées, Main-Class wirée) |
| `scripts/chappe` | script shell qui exec `java -jar` le shaded jar |

## Usage local

```bash
java --enable-preview -jar chappe-cli/target/chappe-cli-*-shaded.jar serve \
  --root /var/www/site --port 8080 --gzip
```

Ou via le script wrapper :

```bash
export CHAPPE_HOME=/opt/chappe
chappe serve --config /etc/chappe/config.yml
```

## Configuration YAML

Format aligné sur `vidocq-docs/chappe-config.yml`. Subset documenté (maps imbriquées,
listes inline/bloc, scalaires string/int/bool, commentaires `#`). **Pas** d'anchors,
de tags `!!`, de multilignes `|`/`>`.

```yaml
server:
  port: 8080
  bind: 0.0.0.0

static:
  root: /var/www/site
  fallback: /404.html              # servi avec status 404
  spa-fallback: /index.html        # mutuellement exclusif avec fallback (SPA mode, 200)
  index-files: [index.html]
  cache-control: "max-age=3600, public"
  gzip: true                        # négocié via Accept-Encoding (sidecars + on-the-fly)

headers:
  always:                           # injectés sur chaque réponse
    X-Content-Type-Options: "nosniff"
    Referrer-Policy: "strict-origin-when-cross-origin"
  staging:                          # actif si l'env var STAGING=true
    X-Robots-Tag: "noindex, nofollow"

logging:
  level: INFO
  access-log: false
```

## Flags CLI (override le YAML)

| Flag | Effet |
|---|---|
| `--config FILE` | charge un fichier YAML |
| `--root DIR` | override `static.root` |
| `--port N` | override `server.port` (défaut 8080) |
| `--bind ADDR` | override `server.bind` (défaut 0.0.0.0) |
| `--fallback PATH` | override `static.fallback` (404) |
| `--spa-fallback PATH` | active le mode SPA (200 sur 404) |
| `--cache-control STR` | override `static.cache-control` |
| `--gzip` / `--no-gzip` | active/désactive la compression |
| `--header KEY=VALUE` | ajoute un header (répétable, merge dans `headers.always`) |
| `-h, --help` | affiche l'aide |

## Compression

`--gzip` (ou `static.gzip: true`) :
- sert en priorité les sidecars `.br` ou `.gz` quand présents (zero-copy via `FileChannel.transferTo`),
- sinon compresse à la volée pour les `Content-Type` text-like > 1 Ko, en émettant
  `Content-Encoding: gzip` et `Vary: Accept-Encoding`,
- skip automatique si `Cache-Control: no-transform` ou `Content-Encoding` déjà fixé.

Brotli n'est pas généré au runtime (le JDK 25 ne fournit pas d'encodeur brotli) mais
les sidecars `.br` produits par un pipeline build (gulp/antora/…) sont servis si
`Accept-Encoding: br`. Côté Maven, le plugin `chappe-static-index-maven-plugin`
peut générer les sidecars `.gz` au build :

```xml
<plugin>
  <groupId>io.vidocq.chappe</groupId>
  <artifactId>chappe-static-index-maven-plugin</artifactId>
  <executions>
    <execution>
      <goals><goal>index</goal></goals>
      <configuration><compress>gzip</compress></configuration>
    </execution>
  </executions>
</plugin>
```

## Docker

### Image fat-jar (simple, ~250 Mo avec Temurin slim)

```dockerfile
FROM eclipse-temurin:25-jre AS runtime
COPY chappe-cli/target/chappe-cli-*-shaded.jar /opt/chappe/lib/chappe-cli-shaded.jar
COPY chappe-cli/src/main/scripts/chappe /opt/chappe/bin/chappe
ENV PATH=/opt/chappe/bin:$PATH CHAPPE_HOME=/opt/chappe
COPY site/ /var/www/site
COPY config.yml /etc/chappe/config.yml
EXPOSE 8080
ENTRYPOINT ["chappe", "serve", "--config", "/etc/chappe/config.yml"]
```

### Image jlink (~50 Mo, runtime Java minimal)

Génération manuelle (à intégrer dans un script CI) :

```bash
jlink \
  --module-path "chappe-cli/target:$JAVA_HOME/jmods" \
  --add-modules io.vidocq.chappe.cli,jdk.crypto.ec,jdk.localedata \
  --launcher chappe=io.vidocq.chappe.cli/io.vidocq.chappe.cli.Main \
  --enable-preview \
  --strip-debug --no-man-pages --no-header-files \
  --output target/chappe-runtime
```

Puis :

```dockerfile
FROM debian:bookworm-slim
COPY target/chappe-runtime /opt/chappe
ENV PATH=/opt/chappe/bin:$PATH
COPY site/ /var/www/site
ENTRYPOINT ["chappe", "serve", "--root", "/var/www/site"]
```

> **Note** : `jlink` requiert que les modules transitifs (chappe-api, chappe-core,
> chappe-http) soient sur le `--module-path` aux côtés du jar `chappe-cli`.
> Pour automatiser, utiliser `mvn dependency:copy-dependencies` puis pointer
> `--module-path` sur le répertoire résultant.

## Limitations connues

- Mini-YAML : pas de multilignes, anchors, tags. Le format suffit pour les configs
  type `chappe-config.yml`. Pour des configs plus complexes, écrire un launcher Java.
- TLS : non géré par la CLI (la config `chappe serve` est cleartext). Pour HTTPS,
  placer le serveur derrière un reverse proxy (nginx, Caddy) ou écrire un launcher.
- Hot reload de la config : non supporté. Un changement nécessite un redémarrage.
- Brotli runtime : non généré. Précompresser au build (sidecars `.br`).
