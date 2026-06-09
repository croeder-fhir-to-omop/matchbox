package ch.ahdis.matchbox.mappinglanguage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Generates deterministic, idempotent OMOP integer IDs from FHIR resource
 * identifiers using a truncated SHA-256 hash.
 *
 * Designed to be called from FML via TransformSupportServices.translate() with
 * the synthetic URL prefix {@code http://omop/id-registry/{ResourceType}}.
 *
 * Properties:
 * - Same (resourceType, fhirId) always produces the same integer (deterministic).
 * - FK consistency: stableId("Patient","x") returns the same value from any table.
 * - Resource-type isolated: stableId("Patient","x") != stableId("Measurement","x").
 * - Range: 1 <= id < 2_000_000_000 (fits OMOP INTEGER / 32-bit signed).
 *
 * See fhir-omop-ig#11.
 */
public class OmopIdRegistry {

    public static final String URL_PREFIX = "http://omop/id-registry/";

    private OmopIdRegistry() {}

    /**
     * Returns a stable integer ID for the given FHIR resource type and ID.
     *
     * @param resourceType OMOP resource type string, e.g. "Patient", "Measurement"
     * @param fhirId       the FHIR resource ID (bare, no "ResourceType/" prefix)
     * @return deterministic positive long in range [1, 2_000_000_000)
     */
    public static long stableId(String resourceType, String fhirId) {
        String key = (resourceType != null ? resourceType : "") + ":"
                   + (fhirId       != null ? fhirId       : "");
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(key.getBytes(StandardCharsets.UTF_8));
            long val = 0;
            for (int i = 0; i < 8; i++) {
                val = (val << 8) | (hash[i] & 0xFFL);
            }
            // keep positive, stay below OMOP INTEGER max
            long id = (val & Long.MAX_VALUE) % 1_999_999_999L + 1L;
            return id;
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by the Java spec — this cannot happen
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Like {@link #stableId} but accepts a FHIR reference string of the form
     * {@code "ResourceType/id"} and strips the prefix automatically.
     *
     * @param resourceType OMOP resource type, e.g. "Patient"
     * @param reference    FHIR reference, e.g. "Patient/pat-123" or bare "pat-123"
     */
    public static long stableIdFromReference(String resourceType, String reference) {
        String fhirId = reference;
        if (fhirId != null && fhirId.contains("/")) {
            fhirId = fhirId.substring(fhirId.lastIndexOf('/') + 1);
        }
        return stableId(resourceType, fhirId);
    }

    /** Returns true if {@code url} is an OMOP id-registry intercept URL. */
    public static boolean isIdRegistryUrl(String url) {
        return url != null && url.startsWith(URL_PREFIX);
    }

    /** Extracts the resource type from an id-registry URL, e.g. "Patient". */
    public static String resourceTypeFromUrl(String url) {
        return url.substring(URL_PREFIX.length());
    }
}
