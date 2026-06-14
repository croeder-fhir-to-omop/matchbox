package ch.ahdis.matchbox.engine.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StructureMap;
import org.hl7.fhir.r5.model.ConceptMap;
import org.hl7.fhir.r5.model.Enumerations.ConceptMapRelationship;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import ch.ahdis.matchbox.engine.MatchboxEngine;
import ch.ahdis.matchbox.engine.MatchboxEngine.MatchboxEngineBuilder;

/**
 * Tests that StructureMapUtilities.translate() preserves the system of a Coding
 * element-model variable extracted via FML rule "src.coding as sc".
 *
 * Bug: when sc is bound via "s.coding as sc" in FML, StructureMapUtilities.translate()
 * must extract system from the element-model Coding via getProperty("system", ...).
 * If the system is lost, a two-group ConceptMap whose groups differ only by source system
 * will match the wrong group (first in list) instead of the group whose source matches
 * the actual Coding system.
 *
 * The two-group ConceptMap makes the difference observable without requiring a live
 * terminology server: correct system → correct group; dropped system → wrong group.
 */
class TranslateCodingSystemTests {

	static final String LOINC_SYSTEM   = "http://loinc.org";
	static final String SNOMED_SYSTEM  = "http://snomed.info/sct";
	static final String OUTPUT_SYSTEM  = "http://omop.org/concept";
	static final String SHARED_CODE    = "SHARED-CODE";
	static final String LOINC_TARGET   = "LOINC-TARGET";
	static final String SNOMED_TARGET  = "SNOMED-TARGET";
	static final String CM_URL         = "http://test/multi-system-cm";
	static final String MAP_URL        = "http://test/translate-system-preservation";

	static MatchboxEngine sharedEngine;

	@BeforeAll
	static void setUpBeforeClass() throws Exception {
		sharedEngine = new MatchboxEngineBuilder().getEngineR4();
		sharedEngine.getContext().cacheResource(buildTwoGroupConceptMap());
	}

	@AfterAll
	static void tearDownAfterClass() {
		sharedEngine = null;
	}

	/**
	 * A ConceptMap with two groups. Both groups map SHARED_CODE but from different
	 * source systems to different target codes.
	 *
	 * Group 1 (LOINC): SHARED-CODE → LOINC-TARGET
	 * Group 2 (SNOMED): SHARED-CODE → SNOMED-TARGET
	 *
	 * When translate() is called with a Coding {system=SNOMED, code=SHARED-CODE}:
	 *   - system preserved → Group 2 selected  → "SNOMED-TARGET"  [correct]
	 *   - system dropped   → !hasSystem → code found in Group 1 first → "LOINC-TARGET" [bug]
	 */
	static ConceptMap buildTwoGroupConceptMap() {
		ConceptMap cm = new ConceptMap();
		cm.setUrl(CM_URL);

		// Group 1: LOINC source
		ConceptMap.ConceptMapGroupComponent g1 = cm.addGroup();
		g1.setSource(LOINC_SYSTEM);
		g1.setTarget(OUTPUT_SYSTEM);
		ConceptMap.SourceElementComponent e1 = g1.addElement();
		e1.setCode(SHARED_CODE);
		ConceptMap.TargetElementComponent t1 = e1.addTarget();
		t1.setCode(LOINC_TARGET);
		t1.setRelationship(ConceptMapRelationship.EQUIVALENT);

		// Group 2: SNOMED source (same code, different target)
		ConceptMap.ConceptMapGroupComponent g2 = cm.addGroup();
		g2.setSource(SNOMED_SYSTEM);
		g2.setTarget(OUTPUT_SYSTEM);
		ConceptMap.SourceElementComponent e2 = g2.addElement();
		e2.setCode(SHARED_CODE);
		ConceptMap.TargetElementComponent t2 = e2.addTarget();
		t2.setCode(SNOMED_TARGET);
		t2.setRelationship(ConceptMapRelationship.EQUIVALENT);

		return cm;
	}

	/**
	 * FML map that extracts src.code.coding as sc and translates sc using the
	 * two-group ConceptMap. The result is assigned to tgt.category (CodeableConcept).
	 */
	static final String TRANSLATE_SYSTEM_MAP = """
		map "%s" = "TranslateSystemPreservation"

		uses "http://hl7.org/fhir/StructureDefinition/Observation" alias Observation as source
		uses "http://hl7.org/fhir/StructureDefinition/Observation" alias Observation as target

		group TranslateTest(source src : Observation, target tgt : Observation) {
		    src.code as cc -> tgt then {
		        cc.coding as sc -> tgt then {
		            sc -> tgt.category = translate(sc, '%s', 'CodeableConcept') "TranslateCodingSystem";
		        };
		    };
		}
		""".formatted(MAP_URL, CM_URL);

	/** Source Observation whose code Coding uses the SNOMED system. */
	static final String OBS_WITH_SNOMED_CODING = """
		{
		    "resourceType": "Observation",
		    "id": "test-snomed",
		    "status": "final",
		    "code": {
		        "coding": [{
		            "system": "%s",
		            "code": "%s",
		            "display": "Shared test code"
		        }]
		    }
		}
		""".formatted(SNOMED_SYSTEM, SHARED_CODE);

	/** Source Observation whose code Coding uses the LOINC system. */
	static final String OBS_WITH_LOINC_CODING = """
		{
		    "resourceType": "Observation",
		    "id": "test-loinc",
		    "status": "final",
		    "code": {
		        "coding": [{
		            "system": "%s",
		            "code": "%s",
		            "display": "Shared test code"
		        }]
		    }
		}
		""".formatted(LOINC_SYSTEM, SHARED_CODE);


	@Nested
	@DisplayName("WHEN FML translates a Coding element extracted via 'src.coding as sc'")
	class WhenFmlTranslatesCodingVariable {

		@Test
		@DisplayName("SHOULD use SNOMED group when source Coding has SNOMED system")
		void SHOULD_select_snomed_group_when_coding_has_snomed_system() throws Exception {
			MatchboxEngine engine = new MatchboxEngine(sharedEngine);
			StructureMap sm = engine.parseMap(TRANSLATE_SYSTEM_MAP);
			assertNotNull(sm, "FML map should parse without error");
			engine.addCanonicalResource(sm);

			Resource result = engine.transformToFhir(OBS_WITH_SNOMED_CODING, true, MAP_URL);
			assertNotNull(result, "transform should produce a result");
			assertEquals("Observation", result.getResourceType().name());
			Observation obs = (Observation) result;

			assertNotNull(obs.getCategoryFirstRep(), "category should be populated by translate()");
			String code = obs.getCategoryFirstRep().getCodingFirstRep().getCode();
			assertEquals(SNOMED_TARGET, code,
				"translate() should select SNOMED group (system preserved); got '" + code
				+ "' — if '" + LOINC_TARGET + "' the system was dropped and first group was used instead");
		}

		@Test
		@DisplayName("SHOULD use LOINC group when source Coding has LOINC system")
		void SHOULD_select_loinc_group_when_coding_has_loinc_system() throws Exception {
			MatchboxEngine engine = new MatchboxEngine(sharedEngine);
			StructureMap sm = engine.parseMap(TRANSLATE_SYSTEM_MAP);
			assertNotNull(sm, "FML map should parse without error");
			engine.addCanonicalResource(sm);

			Resource result = engine.transformToFhir(OBS_WITH_LOINC_CODING, true, MAP_URL);
			assertNotNull(result, "transform should produce a result");
			assertEquals("Observation", result.getResourceType().name());
			Observation obs = (Observation) result;

			assertNotNull(obs.getCategoryFirstRep(), "category should be populated by translate()");
			String code = obs.getCategoryFirstRep().getCodingFirstRep().getCode();
			assertEquals(LOINC_TARGET, code,
				"translate() should select LOINC group (system preserved); got '" + code + "'");
		}
	}
}
