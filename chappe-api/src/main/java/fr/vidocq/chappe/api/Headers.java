package fr.vidocq.chappe.api;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * En-têtes HTTP — liste ordonnée de paires nom/valeur.
 * <p>
 * La recherche par nom est insensible à la casse (RFC 9110, Section 5.1).
 * Les noms dupliqués sont autorisés.
 */
public interface Headers extends Iterable<Headers.Entry> {

    /** Paire nom/valeur d'un en-tête HTTP. */
    record Entry(String name, String value) {}

    /** Retourne la première valeur pour le nom donné, ou vide. */
    Optional<String> first(String name);

    /** Retourne toutes les valeurs pour le nom donné. */
    List<String> all(String name);

    /** Vérifie la présence d'un en-tête avec ce nom. */
    boolean contains(String name);

    /** Nombre total d'entrées (incluant les duplicats). */
    int size();

    /** {@code true} si aucune entrée. */
    default boolean isEmpty() {
        return size() == 0;
    }

    @Override
    Iterator<Entry> iterator();

    /** Crée un builder pour construire des {@code Headers}. */
    static Builder builder() {
        return new DefaultHeadersBuilder();
    }

    /** Retourne des headers vides (singleton). */
    static Headers empty() {
        return DefaultHeaders.EMPTY;
    }

    /** Raccourci pour un seul en-tête. */
    static Headers of(String name, String value) {
        return builder().add(name, value).build();
    }

    /** Builder pour construire des {@code Headers} de manière fluide. */
    interface Builder {

        /** Ajoute une entrée (les duplicats sont conservés). */
        Builder add(String name, String value);

        /** Remplace toutes les entrées portant ce nom par une seule valeur. */
        Builder set(String name, String value);

        /** Construit les {@code Headers} immutables. */
        Headers build();
    }
}
