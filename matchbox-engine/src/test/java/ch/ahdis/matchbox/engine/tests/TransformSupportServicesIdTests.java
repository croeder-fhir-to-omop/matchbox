package ch.ahdis.matchbox.engine.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;

import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.r5.context.IWorkerContext;
import org.hl7.fhir.r5.model.Coding;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import ch.ahdis.matchbox.engine.MatchboxEngine;
import ch.ahdis.matchbox.engine.MatchboxEngine.MatchboxEngineBuilder;
import ch.ahdis.matchbox.mappinglanguage.OmopIdRegistry;
import ch.ahdis.matchbox.mappinglanguage.TransformSupportServices;

/**
 * Integration tests for TransformSupportServices.translate() with OMOP id-registry URLs.
 * Verifies that translate() correctly intercepts id-registry URLs and returns
 * stable integer IDs. See fhir-omop-ig#11.
 */
class TransformSupportServicesIdTests {

    static MatchboxEngine engine;
    static IWorkerContext ctx;
    static TransformSupportServices svc;

    @BeforeAll
    static void setUp() throws Exception {
        engine = new MatchboxEngineBuilder().getEngineR4();
        ctx    = engine.getContext();
        svc    = new TransformSupportServices(ctx, new ArrayList<>());
    }

    @AfterAll
    static void tearDown() {
        engine = null;
        ctx    = null;
        svc    = null;
    }

    private Coding coding(String code) {
        return new Coding().setCode(code);
    }

    @Nested
    @DisplayName("WHEN translate() is called with an id-registry URL")
    class IdRegistryTranslate {

        @Test
        @DisplayName("SHOULD return a non-null Coding")
        void test_WHEN_id_registry_url_SHOULD_return_non_null_coding() throws FHIRException {
            Coding result = svc.translate(null, coding("pat-1"), "http://omop/id-registry/Patient");
            assertNotNull(result, "Expected non-null Coding for id-registry URL");
        }

        @Test
        @DisplayName("SHOULD return Coding with numeric code")
        void test_WHEN_id_registry_url_SHOULD_return_numeric_code() throws FHIRException {
            Coding result = svc.translate(null, coding("pat-1"), "http://omop/id-registry/Patient");
            assertNotNull(result.getCode());
            assertDoesNotThrow(() -> Long.parseLong(result.getCode()),
                "Expected numeric code, got: " + result.getCode());
        }

        @Test
        @DisplayName("SHOULD return positive integer code")
        void test_WHEN_id_registry_url_SHOULD_return_positive_code() throws FHIRException {
            Coding result = svc.translate(null, coding("pat-1"), "http://omop/id-registry/Patient");
            long id = Long.parseLong(result.getCode());
            assertTrue(id > 0, "Expected positive id, got " + id);
        }

        @Test
        @DisplayName("SHOULD return code within OMOP INTEGER range")
        void test_WHEN_id_registry_url_SHOULD_return_code_within_omop_range() throws FHIRException {
            Coding result = svc.translate(null, coding("pat-1"), "http://omop/id-registry/Patient");
            long id = Long.parseLong(result.getCode());
            assertTrue(id < 2_000_000_000L, "Expected id < 2B, got " + id);
        }

        @Test
        @DisplayName("SHOULD return deterministic code for same input")
        void test_WHEN_same_source_and_url_SHOULD_return_same_code() throws FHIRException {
            Coding r1 = svc.translate(null, coding("pat-abc"), "http://omop/id-registry/Patient");
            Coding r2 = svc.translate(null, coding("pat-abc"), "http://omop/id-registry/Patient");
            assertEquals(r1.getCode(), r2.getCode(),
                "translate() must be deterministic for same source+url");
        }

        @Test
        @DisplayName("SHOULD return different codes for different resource types")
        void test_WHEN_different_resource_type_url_SHOULD_return_different_code() throws FHIRException {
            Coding patient     = svc.translate(null, coding("rec-1"), "http://omop/id-registry/Patient");
            Coding measurement = svc.translate(null, coding("rec-1"), "http://omop/id-registry/Measurement");
            assertNotEquals(patient.getCode(), measurement.getCode(),
                "Different resource type URLs must produce different codes");
        }

        @Test
        @DisplayName("SHOULD return id-registry system on the Coding")
        void test_WHEN_id_registry_url_SHOULD_return_registry_system() throws FHIRException {
            Coding result = svc.translate(null, coding("pat-1"), "http://omop/id-registry/Patient");
            assertEquals("http://omop/id-registry", result.getSystem());
        }
    }

    @Nested
    @DisplayName("WHEN translate() is called with a reference code containing a prefix")
    class ReferenceStripping {

        @Test
        @DisplayName("SHOULD produce same id for 'Patient/pat-1' as for bare 'pat-1'")
        void test_WHEN_reference_with_prefix_SHOULD_strip_and_match_bare_id() throws FHIRException {
            Coding withPrefix  = svc.translate(null, coding("Patient/pat-1"), "http://omop/id-registry/Patient");
            Coding withoutPrefix = svc.translate(null, coding("pat-1"),       "http://omop/id-registry/Patient");
            assertEquals(withoutPrefix.getCode(), withPrefix.getCode(),
                "translate() should strip resource type prefix from reference codes");
        }
    }

    @Nested
    @DisplayName("WHEN translate() is called with a non-registry URL")
    class NonRegistryUrl {

        @Test
        @DisplayName("SHOULD NOT intercept — should fall through to ConceptMapEngine")
        void test_WHEN_non_registry_url_SHOULD_not_intercept() {
            // A ConceptMap that doesn't exist → ConceptMapEngine throws or returns null
            // The key assertion is that it does NOT return a numeric id-registry code
            assertThrows(FHIRException.class, () ->
                svc.translate(null, coding("some-code"),
                    "http://hl7.org/fhir/uv/omop/ConceptMap/NonExistent")
            );
        }
    }

    @Nested
    @DisplayName("WHEN translate() is used for FK consistency")
    class FkConsistency {

        @Test
        @DisplayName("SHOULD produce same person_id from PersonMap and MeasurementMap contexts")
        void test_WHEN_same_patient_id_SHOULD_produce_consistent_fk() throws FHIRException {
            // PersonMap: src.id → tgt.person_id via 'http://omop/id-registry/Patient'
            Coding fromPersonMap      = svc.translate(null, coding("pat-xyz"), "http://omop/id-registry/Patient");
            // MeasurementMap: src.subject.reference → tgt.person_id, reference = "Patient/pat-xyz"
            Coding fromMeasurementMap = svc.translate(null, coding("Patient/pat-xyz"), "http://omop/id-registry/Patient");
            assertEquals(fromPersonMap.getCode(), fromMeasurementMap.getCode(),
                "person_id from PersonMap and MeasurementMap must be identical for same patient");
        }
    }
}
