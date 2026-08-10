package ch.ahdis.matchbox.engine.tests;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import ch.ahdis.matchbox.mappinglanguage.OmopIdRegistry;

/**
 * Unit tests for OmopIdRegistry.stableId() — deterministic OMOP integer ID
 * generation from FHIR resource identifiers. See fhir-omop-ig#11.
 */
class OmopIdRegistryTests {

    @Nested
    @DisplayName("WHEN stableId() is called")
    class ReturnType {

        @Test
        @DisplayName("SHOULD return a positive long")
        void test_WHEN_called_SHOULD_return_positive_long() {
            long id = OmopIdRegistry.stableId("Patient", "pat-1");
            assertTrue(id > 0, "Expected positive id, got " + id);
        }

        @Test
        @DisplayName("SHOULD return value within OMOP safe integer range (< 2_000_000_000)")
        void test_WHEN_called_SHOULD_return_value_within_omop_range() {
            long id = OmopIdRegistry.stableId("Patient", "pat-1");
            assertTrue(id < 2_000_000_000L, "Expected id < 2B for OMOP INTEGER compat, got " + id);
        }
    }

    @Nested
    @DisplayName("WHEN stableId() is called with the same arguments")
    class Determinism {

        @Test
        @DisplayName("SHOULD return the same value on repeated calls")
        void test_WHEN_same_args_SHOULD_return_same_value() {
            assertEquals(
                OmopIdRegistry.stableId("Patient", "pat-abc"),
                OmopIdRegistry.stableId("Patient", "pat-abc")
            );
        }

        @Test
        @DisplayName("SHOULD be stable across 20 invocations")
        void test_WHEN_called_20_times_SHOULD_always_return_same_value() {
            long expected = OmopIdRegistry.stableId("Measurement", "obs-42");
            for (int i = 0; i < 20; i++) {
                assertEquals(expected, OmopIdRegistry.stableId("Measurement", "obs-42"),
                    "stableId should be deterministic on call " + i);
            }
        }

        @Test
        @DisplayName("SHOULD provide FK consistency: same patient ID from person and measurement tables")
        void test_WHEN_used_for_fk_SHOULD_match_across_tables() {
            long fromPersonTable      = OmopIdRegistry.stableId("Patient", "patient-xyz");
            long fromMeasurementTable = OmopIdRegistry.stableId("Patient", "patient-xyz");
            assertEquals(fromPersonTable, fromMeasurementTable,
                "person_id FK must be identical regardless of which table computes it");
        }
    }

    @Nested
    @DisplayName("WHEN stableId() is called with different inputs")
    class Isolation {

        @Test
        @DisplayName("SHOULD return different values for different fhir IDs")
        void test_WHEN_different_fhir_ids_SHOULD_return_different_values() {
            assertNotEquals(
                OmopIdRegistry.stableId("Patient", "pat-1"),
                OmopIdRegistry.stableId("Patient", "pat-2")
            );
        }

        @Test
        @DisplayName("SHOULD return different values for different resource types with same fhir ID")
        void test_WHEN_different_resource_types_same_fhir_id_SHOULD_return_different_values() {
            assertNotEquals(
                OmopIdRegistry.stableId("Patient", "rec-1"),
                OmopIdRegistry.stableId("Measurement", "rec-1")
            );
        }

        @Test
        @DisplayName("SHOULD return different values for ConditionOccurrence vs ProcedureOccurrence")
        void test_WHEN_condition_vs_procedure_SHOULD_differ() {
            assertNotEquals(
                OmopIdRegistry.stableId("ConditionOccurrence", "x"),
                OmopIdRegistry.stableId("ProcedureOccurrence", "x")
            );
        }
    }

    @Nested
    @DisplayName("WHEN stableId() is called with edge-case fhir IDs")
    class EdgeCases {

        @Test
        @DisplayName("SHOULD handle empty fhir ID without throwing")
        void test_WHEN_fhir_id_is_empty_SHOULD_not_throw() {
            assertDoesNotThrow(() -> OmopIdRegistry.stableId("Patient", ""));
        }

        @Test
        @DisplayName("SHOULD handle null fhir ID without throwing")
        void test_WHEN_fhir_id_is_null_SHOULD_not_throw() {
            assertDoesNotThrow(() -> OmopIdRegistry.stableId("Patient", null));
        }

        @Test
        @DisplayName("SHOULD handle UUID-style fhir IDs")
        void test_WHEN_fhir_id_is_uuid_SHOULD_return_valid_long() {
            long id = OmopIdRegistry.stableId("Patient", "urn:uuid:550e8400-e29b-41d4-a716-446655440000");
            assertTrue(id > 0 && id < 2_000_000_000L);
        }

        @Test
        @DisplayName("SHOULD handle very long fhir IDs")
        void test_WHEN_fhir_id_is_very_long_SHOULD_return_valid_long() {
            String longId = "x".repeat(512);
            long id = OmopIdRegistry.stableId("Patient", longId);
            assertTrue(id > 0 && id < 2_000_000_000L);
        }
    }

    @Nested
    @DisplayName("WHEN stableIdFromReference() is called with a FHIR reference string")
    class FromReference {

        @Test
        @DisplayName("SHOULD strip 'Patient/' prefix and produce same id as bare patient ID")
        void test_WHEN_reference_has_patient_prefix_SHOULD_strip_it() {
            long fromBareId    = OmopIdRegistry.stableId("Patient", "pat-123");
            long fromReference = OmopIdRegistry.stableIdFromReference("Patient", "Patient/pat-123");
            assertEquals(fromBareId, fromReference,
                "stableIdFromReference should strip 'Patient/' prefix");
        }

        @Test
        @DisplayName("SHOULD strip 'Encounter/' prefix")
        void test_WHEN_reference_has_encounter_prefix_SHOULD_strip_it() {
            long fromBareId    = OmopIdRegistry.stableId("VisitOccurrence", "enc-456");
            long fromReference = OmopIdRegistry.stableIdFromReference("VisitOccurrence", "Encounter/enc-456");
            assertEquals(fromBareId, fromReference);
        }

        @Test
        @DisplayName("SHOULD return same as stableId() when reference has no prefix")
        void test_WHEN_reference_has_no_prefix_SHOULD_behave_like_stableId() {
            assertEquals(
                OmopIdRegistry.stableId("Patient", "pat-abc"),
                OmopIdRegistry.stableIdFromReference("Patient", "pat-abc")
            );
        }

        @Test
        @DisplayName("SHOULD handle null reference without throwing")
        void test_WHEN_reference_is_null_SHOULD_not_throw() {
            assertDoesNotThrow(() -> OmopIdRegistry.stableIdFromReference("Patient", null));
        }
    }

    @Nested
    @DisplayName("WHEN a deployment salt is configured")
    class SaltBehavior {

        @AfterEach
        void resetSalt() {
            OmopIdRegistry.setSalt("");
        }

        @Test
        @DisplayName("SHOULD produce a different ID than the unsalted value")
        void test_WHEN_salt_is_set_SHOULD_differ_from_unsalted() {
            long unsalted = OmopIdRegistry.stableId("Patient", "pat-1");
            OmopIdRegistry.setSalt("deployment-A");
            long salted = OmopIdRegistry.stableId("Patient", "pat-1");
            assertNotEquals(unsalted, salted,
                "Salted ID must differ from unsalted ID for the same input");
        }

        @Test
        @DisplayName("SHOULD produce different IDs for different salts")
        void test_WHEN_different_salts_SHOULD_produce_different_ids() {
            OmopIdRegistry.setSalt("org-A");
            long idA = OmopIdRegistry.stableId("Patient", "pat-1");
            OmopIdRegistry.setSalt("org-B");
            long idB = OmopIdRegistry.stableId("Patient", "pat-1");
            assertNotEquals(idA, idB,
                "Different salts must produce different IDs for the same FHIR input");
        }

        @Test
        @DisplayName("SHOULD remain deterministic: same salt + same input → same ID")
        void test_WHEN_same_salt_and_input_SHOULD_be_deterministic() {
            OmopIdRegistry.setSalt("my-salt");
            long first  = OmopIdRegistry.stableId("Patient", "pat-1");
            long second = OmopIdRegistry.stableId("Patient", "pat-1");
            assertEquals(first, second,
                "stableId must be deterministic for a given salt");
        }

        @Test
        @DisplayName("SHOULD preserve FK consistency under a salt: same patient ID from any table")
        void test_WHEN_salt_set_SHOULD_preserve_fk_consistency() {
            OmopIdRegistry.setSalt("shared-salt");
            long fromPerson      = OmopIdRegistry.stableId("Patient", "pat-xyz");
            long fromMeasurement = OmopIdRegistry.stableId("Patient", "pat-xyz");
            assertEquals(fromPerson, fromMeasurement,
                "FK consistency must hold under a salt: same input always yields same integer");
        }

        @Test
        @DisplayName("SHOULD preserve backward compat: empty salt produces same IDs as no-salt")
        void test_WHEN_salt_is_empty_SHOULD_equal_unsalted_behavior() {
            long withoutCall = OmopIdRegistry.stableId("Patient", "pat-abc");
            OmopIdRegistry.setSalt("");
            long withEmptySalt = OmopIdRegistry.stableId("Patient", "pat-abc");
            assertEquals(withoutCall, withEmptySalt,
                "Empty salt must produce the same IDs as the default (no setSalt call)");
        }

        @Test
        @DisplayName("SHOULD treat null salt same as empty string")
        void test_WHEN_salt_is_null_SHOULD_behave_like_empty() {
            long baseline = OmopIdRegistry.stableId("Patient", "pat-1");
            OmopIdRegistry.setSalt(null);
            assertEquals("", OmopIdRegistry.getSalt(), "null salt should normalise to empty string");
            assertEquals(baseline, OmopIdRegistry.stableId("Patient", "pat-1"),
                "null salt should produce same IDs as default");
        }

        @Test
        @DisplayName("SHOULD stay within OMOP integer range when salted")
        void test_WHEN_salted_SHOULD_stay_within_omop_range() {
            OmopIdRegistry.setSalt("range-test-salt");
            long id = OmopIdRegistry.stableId("Measurement", "obs-99");
            assertTrue(id > 0 && id < 2_000_000_000L,
                "Salted ID must remain in OMOP safe integer range, got " + id);
        }
    }

    @Nested
    @DisplayName("WHEN isIdRegistryUrl() is called")
    class UrlCheck {

        @Test
        @DisplayName("SHOULD return true for id-registry URLs")
        void test_WHEN_registry_url_SHOULD_return_true() {
            assertTrue(OmopIdRegistry.isIdRegistryUrl("http://omop/id-registry/Patient"));
        }

        @Test
        @DisplayName("SHOULD return false for regular ConceptMap URLs")
        void test_WHEN_conceptmap_url_SHOULD_return_false() {
            assertFalse(OmopIdRegistry.isIdRegistryUrl("http://hl7.org/fhir/uv/omop/ConceptMap/GenderClass"));
        }

        @Test
        @DisplayName("SHOULD return false for null URL")
        void test_WHEN_null_url_SHOULD_return_false() {
            assertFalse(OmopIdRegistry.isIdRegistryUrl(null));
        }

        @Test
        @DisplayName("SHOULD extract resource type from URL")
        void test_WHEN_registry_url_SHOULD_extract_resource_type() {
            assertEquals("Patient", OmopIdRegistry.resourceTypeFromUrl("http://omop/id-registry/Patient"));
            assertEquals("Measurement", OmopIdRegistry.resourceTypeFromUrl("http://omop/id-registry/Measurement"));
        }
    }
}
