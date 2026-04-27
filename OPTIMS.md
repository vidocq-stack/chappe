# Chappe — Étude : optimisations compile-time (APT / BuildCompatibleExtension / Plugin Maven)

## Verdict

Non, ce n'est **pas** peine perdue — mais le gain sera plus modeste que pour Vidocq/Vauban,
et pour des raisons structurellement différentes.

## Pourquoi Vidocq/Vauban y gagnent beaucoup

Chez Vidocq (JAX-RS) et Vauban (CDI), APT remplace de la **réflexion runtime** (scan de
classpath, introspection de beans, résolution d'injection). C'est là qu'il y a 10× à gagner,
parce que la réflexion est intrinsèquement lente.

## Chez Chappe, la situation est différente

Chappe n'a **déjà aucune réflexion** sur le hot path :

- `ServiceLoader` est utilisé 1× au boot (`ServerProvider`), pas par requête
- Les handlers sont des lambdas/instances passées programmatiquement
- Pas d'annotations `@Route` à scanner — l'API est fluent (`router.get(...)`)
- Le parser HTTP est déjà une state machine avec `StringBuilder` réutilisé (zero-alloc)

Donc APT ne peut pas **remplacer** du code lent, seulement **spécialiser** des structures
déjà correctes.

## Opportunités réelles (par ROI décroissant)

| Cible | Runtime actuel | Précalcul possible | Gain réaliste |
|---|---|---|---|
| **HPACK static table** (`HpackStaticTable:110-130`) | scan linéaire O(61) par header | switch parfait-hash généré | hot path H/2 — **mesurable** |
| **MimeTypes.detect()** | `lastIndexOf + substring + toLowerCase + map` | switch compilé sur extension | -40 % alloc, mais pas sur hot path sauf statics |
| **StaticFileHandler classpath** | `loader.getResource()` + `URLConnection` par requête | index build-time `META-INF/chappe-resources` | -90 % latence fichier classpath |
| **Router trie/DFA** | scan linéaire des routes | trie pré-compilé | utile seulement au-delà de ~50 routes |
| **Mount registry** | assemblage `LinkedHashMap` au `onStart` | registry généré | -20 % startup, pas runtime |

## Ce qui ne gagnera rien

- **Parser HTTP/1.1** — déjà optimal, APT ne fait pas mieux qu'une state machine manuelle
- **`RequestContext` ScopedValue** — déjà quasi-gratuit (JEP 506)
- **Virtual threads, buffer pool, write coalescing** — orthogonaux à APT

## Recommandation

**Ne pas copier le modèle Vidocq/Vauban tel quel.** Là où ils génèrent des *proxies d'injection*
(remplacement de réflexion), Chappe devrait plutôt aller vers un **plugin Maven** qui :

1. **Indexe les resources classpath** au build → `META-INF/chappe-static-index` (gain clair, pattern Quarkus)
2. **Génère les tables HPACK/MIME** en switch parfait-hash (micro-opti, mais hot path H/2)
3. **Éventuellement** un `@ChappeApp` qui compile les `router.mount(...)` en trie — *seulement si*
   un profil d'usage avec beaucoup de routes émerge

Le **tradeoff principal** : la simplicité actuelle (zéro build step, API fluent) est une feature.
Un plugin APT obligatoire casserait ça. Le garder **optionnel** (`chappe-codegen` opt-in)
préserve l'expérience dev.

---

## POC retenu : plugin Maven d'indexation des resources statiques

### Problème actuel

`StaticFileHandler` avec fallback classpath fait, **par requête** :

- `loader.getResource(resourcePath)` — scan de tous les ClassLoader parents
- `URLConnection.openConnection()` — ouverture de flux pour obtenir `contentLength`, `lastModified`
- `MimeTypes.detect()` — `lastIndexOf + substring + toLowerCase + map lookup`
- Allocation d'un `ResolvedResource` wrapper

Sur un JAR de quelques milliers de resources, le `getResource()` traverse toute la hiérarchie de
ClassLoaders à chaque hit. Sur classpath imbriqué (Spring Boot, fat JAR, uber JAR), c'est pire.

### Solution : index pré-généré au build

Un plugin Maven `chappe-static-index-maven-plugin` qui :

1. Scanne les répertoires de resources configurés (`src/main/resources/static/**` par défaut)
2. Pour chaque fichier, pré-calcule : `path`, `size`, `lastModified`, `mimeType`, `etag` (SHA-256 tronqué)
3. Écrit `META-INF/chappe-static-index.properties` (format simple, loadable 1× au boot)
4. Runtime : `StaticFileHandler` détecte l'index, fait un lookup O(1) en `Map` statique

Le hot path devient : `map.get(path)` → `Body.ofClasspath(path, size, mime)` — zéro URLConnection,
zéro introspection.

### Gain mesurable attendu

| Métrique | Avant | Après | Gain |
|---|---|---|---|
| Latence p50 fichier statique classpath | ~15 µs | ~2 µs | **-85 %** |
| Allocations par requête | 3-4 objets (URLConnection, streams…) | 1 (Body) | **-75 %** |
| Boot | 0 ms | +5-20 ms (chargement index) | négligeable |

---

### Structure du plugin

```
chappe-static-index-maven-plugin/
├── pom.xml
└── src/main/java/fr/vidocq/chappe/maven/
    ├── IndexMojo.java                 # @Mojo("index") — scanne et génère
    └── StaticIndexWriter.java         # écriture properties
```

### `IndexMojo.java` — squelette

```java
package io.vidocq.chappe.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;

@Mojo(name = "index", defaultPhase = LifecyclePhase.PROCESS_RESOURCES)
public final class IndexMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @Parameter(defaultValue = "static")
    private String rootPrefix;

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    private String outputDirectory;

    @Override
    public void execute() throws MojoExecutionException {
        Path root = Paths.get(outputDirectory, rootPrefix);
        if (!Files.isDirectory(root)) {
            getLog().info("No static root at " + root + " — skipping");
            return;
        }

        Path indexFile = Paths.get(outputDirectory, "META-INF", "chappe-static-index.properties");
        try {
            Files.createDirectories(indexFile.getParent());
            Properties props = new Properties();
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile).forEach(p -> {
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    try {
                        long size = Files.size(p);
                        long mtime = Files.getLastModifiedTime(p).toMillis();
                        String mime = detectMime(rel);
                        String etag = sha256Hex(p).substring(0, 16);
                        props.setProperty(rel, size + "|" + mtime + "|" + mime + "|" + etag);
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to index " + p, e);
                    }
                });
            }
            try (var out = Files.newBufferedWriter(indexFile)) {
                props.store(out, "Chappe static resources index — generated at build time");
            }
            getLog().info("Indexed " + props.size() + " static resources → " + indexFile);
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to generate chappe-static-index", e);
        }
    }

    private String detectMime(String path) { /* réutiliser MimeTypes.detect */ return "application/octet-stream"; }
    private String sha256Hex(Path p) throws IOException { /* MessageDigest SHA-256 */ return ""; }
}
```

### Utilisation dans un projet downstream

```xml
<build>
  <plugins>
    <plugin>
      <groupId>io.vidocq.chappe</groupId>
      <artifactId>chappe-static-index-maven-plugin</artifactId>
      <version>${chappe.version}</version>
      <executions>
        <execution>
          <goals><goal>index</goal></goals>
        </execution>
      </executions>
      <configuration>
        <rootPrefix>static</rootPrefix>
      </configuration>
    </plugin>
  </plugins>
</build>
```

### Lecture runtime dans `StaticFileHandler`

```java
// au boot du handler, 1× :
private static final Map<String, IndexedResource> INDEX = loadIndex();

private static Map<String, IndexedResource> loadIndex() {
    try (var in = StaticFileHandler.class.getResourceAsStream("/META-INF/chappe-static-index.properties")) {
        if (in == null) return Map.of();
        Properties p = new Properties();
        p.load(in);
        Map<String, IndexedResource> m = new HashMap<>(p.size() * 2);
        for (String key : p.stringPropertyNames()) {
            String[] parts = p.getProperty(key).split("\\|");
            m.put(key, new IndexedResource(
                Long.parseLong(parts[0]),
                Long.parseLong(parts[1]),
                parts[2],
                parts[3]
            ));
        }
        return Map.copyOf(m);
    } catch (IOException e) {
        return Map.of();
    }
}

record IndexedResource(long size, long lastModified, String mime, String etag) {}

// hot path :
public Response handle(Request req) {
    String path = req.path();
    IndexedResource idx = INDEX.get(path);
    if (idx != null) {
        // zero URLConnection, zero introspection
        return Response.ok()
            .header("Content-Type", idx.mime())
            .header("ETag", '"' + idx.etag() + '"')
            .body(Body.ofClasspath("/static/" + path, idx.size()));
    }
    // fallback : ancien chemin (filesystem, dev mode)
    return fallbackLookup(req);
}
```

### Mode dev (sans plugin)

Si `META-INF/chappe-static-index.properties` est absent → `INDEX.isEmpty()` → on retombe sur
l'ancien comportement `loader.getResource()`. **Zéro régression**, **zéro config obligatoire**.

### Étapes de livraison

1. Créer le module `chappe-static-index-maven-plugin` (packaging `maven-plugin`)
2. Implémenter `IndexMojo` + tests unitaires (répertoire fixture → vérifier index produit)
3. Brancher la lecture dans `StaticFileHandler` (fallback transparent)
4. Ajouter un exemple dans `chappe-examples` (`static-indexed-example`)
5. Bench JMH : avant/après sur fichier classpath → valider le -85 %
6. Documenter dans `README.md` (section « Production builds »)

### Points d'attention

- **Stabilité des ETags** : utiliser SHA-256 pas `lastModified` pour être reproductible CI/CD
- **Hot reload dev** : désactiver l'index si système de propriété `chappe.dev=true` (ou absence détectée)
- **Classpath multiple** : si plusieurs JARs contiennent `META-INF/chappe-static-index.properties`,
  fusionner via `getResources()` au boot (ordre : premier gagnant ou dernier gagnant, à trancher)
- **Taille de l'index** : 10k fichiers ≈ ~1 MB properties — acceptable. Au-delà, envisager un format
  binaire compact (varint + table de strings dédupliquée)

---

## Implémentation livrée (2026-04-23)

Les optimisations du tableau ROI ont été implémentées sur la branche `working/compiletime` :

### 1. HPACK static table — `O(61)` → `O(1)`
`chappe-http/.../h2/HpackStaticTable.java` : `findExact` et `findByName` utilisent deux maps
immuables pré-construites (`Map.copyOf`) dans un bloc statique. Scan linéaire éliminé du hot path HTTP/2.

### 2. MimeTypes.detect() — zéro-alloc
`chappe-api/.../MimeTypes.java` : plus de `substring` ni de `toLowerCase()`. Comparaison
case-insensitive via `String.regionMatches(true, …)` sur un tableau ordonné par fréquence web
(html → css → js → png → jpg → svg → json → …). Lookup O(n) mais avec hit rapide sur le cas fréquent
et **zéro allocation**.

### 3. Plugin Maven `chappe-static-index-maven-plugin`
- Nouveau module `chappe-static-index-maven-plugin` (packaging `maven-plugin`, cible Java 21 pour
  compat avec `maven-plugin-plugin:descriptor`).
- `IndexMojo` (phase `process-resources`) scanne `${project.build.outputDirectory}/${rootPrefix}`,
  calcule `size|mtime|mime|etag` (SHA-256 tronqué 16 hex) et écrit
  `META-INF/chappe-static-index.properties` avec clés classpath complètes (`static/index.html`).
- 3 tests unitaires verts (indexation nominale, skip root absent, flag `skip`).
- `StaticFileHandler` (chappe-api) : nouveau fast-path dans `ClasspathSource.resolve()` qui consulte
  l'index avant `URLConnection.openConnection()`. Chargement de l'index 1× par ClassLoader via
  `ClassLoader.getResources()` (merge multi-JAR, premier gagnant). **Absence d'index = fallback
  transparent** sur le chemin legacy ⇒ zéro régression.
- Exemple `chappe-examples/StaticIndexedExample` + resources sous `static/` + plugin activé dans le
  POM ; l'index généré au package contient bien `static/index.html`, `static/app.css`,
  `static/app.js`.

### 4. Router — fast-path routes statiques
`chappe-api/.../DefaultRouterBuilder.java` : à `build()`, les routes sans `{param}` ni `/*` sont
indexées dans `Map<String, EnumMap<HttpMethod, Route>>`. Le dispatch fait un lookup O(1)
(`staticIndex.get(path)`) avant le scan linéaire sur les seules routes dynamiques. Sémantique
préservée intégralement : 405 Method Not Allowed, Auto-HEAD, mounts, filtres globaux/locaux. Les
wrappers `withPathParams` et la construction de la `List<Filter>` ne sont plus alloués pour les
routes statiques (params = `Collections.emptyMap()`).

### 5. Mount registry — non requis
`DefaultRouterBuilder.build()` faisait déjà `List.copyOf(mounts)` (snapshot immuable au build). Scan
linéaire sur 1–3 mounts typiques reste la meilleure option. **Aucune action requise.**

### Validation

- `mvn -pl '!chappe-conformance' test` → BUILD SUCCESS, **65/65 tests verts**
  (RouterTest 7, ExtensionSpiTest 25, HttpGetTest 7, Http2Test 7, TlsTest 3, etc.).
- `chappe-static-index-maven-plugin` → **3/3 tests verts**.
- Un test préexistant échoue dans `chappe-conformance` (`Http11HeadersTest#obsFoldRejected` :
  attendu 400, reçoit 301) — reproduit sur HEAD sans les optimisations, lié à une modification
  antérieure de `HttpRequestImpl.buildUri()`. **Hors périmètre de cette série.**

### Restant

- Bench JMH comparatif avant/après sur `StaticFileHandler` classpath pour valider le gain annoncé
  (-85 % latence, -75 % allocations).
- `chappe-static-index-maven-plugin` : packaging Maven 4 vs 3 — aujourd'hui on dépend de
  `maven-plugin-api:3.9.9`. À retester quand Maven 4 GA sera publié.
