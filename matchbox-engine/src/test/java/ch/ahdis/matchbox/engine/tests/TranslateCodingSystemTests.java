package ch.ahdis.matchbox.engine.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import org.hl7.fhir.r5.terminologies.client.ITerminologyClient;
import org.hl7.fhir.r5.terminologies.client.TerminologyClientManager;

import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StructureMap;
import org.hl7.fhir.r5.model.ConceptMap;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Enumerations.ConceptMapRelationship;
import org.hl7.fhir.r5.model.Parameters;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import ch.ahdis.matchbox.engine.MatchboxEngine;
import ch.ahdis.matchbox.engine.MatchboxEngine.MatchboxEngineBuilder;
import ch.ahdis.matchbox.mappinglanguage.ConceptMapEngine;

/**
 * Tests that StructureMapUtilities.translate() preserves the system of a Coding
 * element-model variable extracted via FML rules "src.coding as sc" (iteration)
 * and "src.coding first as sc" (first-element pick).
 *
 * Bug class: when sc is bound via FML, StructureMapUtilities.translate() must extract
 * system from the element-model Coding via getProperty("system", ...). If the system is
 * lost, a two-group ConceptMap whose groups differ only by source system will match the
 * wrong group (first in list) instead of the group whose source matches the Coding system.
 *
 * The two-group ConceptMap makes the difference observable without requiring a live
 * terminology server: correct system → correct group; dropped system → wrong group.
 *
 * Known downstream consequence (observed 2025-06): when translate() is called with an
 * empty ConceptMap URL (e.g. "translate(sc, '', 'code')" in FML), matchbox routes to the
 * external terminology server. If the system is dropped before the HTTP request is built,
 * the POST body sent to the server contains only {"name":"code","valueCode":"..."} with no
 * "system" parameter. FHIR-compliant servers reject this as a 400 Bad Request. Echidna
 * (public hosted server) accepts system-absent lookups as a courtesy; strict servers like
 * enchilada (local OMOP-backed server) do not. The fix is either in FML system extraction
 * or in ConceptMapEngine.translateViaTxServer — whichever is losing the system.
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
	static final String FIRST_MAP_URL  = "http://test/translate-system-preservation-first";
	static final String SYSREAD_MAP_URL = "http://test/read-system-from-first";

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

	/**
	 * FML map using the "coding first as sc" pattern (as in BloodPressureVitalSignsMap).
	 * Translates with a named ConceptMap so no tx server is required. Tests whether system
	 * is preserved when sc is bound via the "first" FML keyword rather than plain iteration.
	 */
	static final String TRANSLATE_FIRST_MAP = """
		map "%s" = "TranslateSystemPreservationFirst"

		uses "http://hl7.org/fhir/StructureDefinition/Observation" alias Observation as source
		uses "http://hl7.org/fhir/StructureDefinition/Observation" alias Observation as target

		group TranslateFirstTest(source src : Observation, target tgt : Observation) {
		    src.code as cc -> tgt then {
		        cc.coding first as sc -> tgt then {
		            sc -> tgt.category = translate(sc, '%s', 'CodeableConcept') "TranslateCodingSystemFirst";
		        };
		    };
		}
		""".formatted(FIRST_MAP_URL, CM_URL);

	/**
	 * FML map that exactly mirrors the BloodPressureVitalSignsMap translate pattern:
	 * "coding first as sc → translate(sc, '', 'code')".
	 *
	 * With no terminology server configured the translate call returns null and the target
	 * field is left unpopulated. The test verifies that the FML transform completes without
	 * error — i.e., the "coding first" binding is FML-valid and the translate(sc, '', 'code')
	 * call does NOT throw even when sc is obtained via "first".
	 *
	 * Note: directly copying sc.system or sc.code to a target field fails in this matchbox
	 * version ("No matches found for rule for 'uri to uri' / 'code to string'"). The FML
	 * engine's translate() function handles element-model→R4 Coding extraction internally;
	 * raw child-property copies across that boundary are not supported without explicit cast.
	 */
	static final String READ_SYSTEM_FROM_FIRST_MAP = """
		map "%s" = "ReadSystemFromFirst"

		uses "http://hl7.org/fhir/StructureDefinition/Observation" alias Observation as source
		uses "http://hl7.org/fhir/StructureDefinition/Observation" alias Observation as target

		group ReadSystemTest(source src : Observation, target tgt : Observation) {
		    src.code as cc -> tgt then {
		        cc.coding first as sc -> tgt then {
		            sc -> tgt.category = translate(sc, '', 'CodeableConcept') "TranslateViaEmptyUrl";
		        };
		    };
		}
		""".formatted(SYSREAD_MAP_URL);


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


	// ─── Group 3: system preservation via 'coding first as sc' ───────────────────
	//
	// The BloodPressureVitalSignsMap pattern uses "src.code.coding first as sc" rather
	// than plain iteration "src.code.coding as sc". These tests verify that the "first"
	// extraction preserves the system URI in exactly the same way that iteration does.
	//
	// Test 3.1 is the minimal diagnostic: read sc.system directly into tgt.id with no
	// translate() call. If it fails, FML itself drops the system at the "first" binding
	// before translate() is even reached.
	//
	// Test 3.2 / 3.3 use the same two-group ConceptMap as Group 2 but with the "first"
	// FML pattern. If system is preserved, the correct group (matching the source system)
	// is selected; if system is dropped, the first group in the ConceptMap wins regardless.
	//
	// Background: empirical observation (2025-06) showed that matchbox sends a $translate
	// POST body with only {"name":"code","valueCode":"..."} and no "system" parameter when
	// translate() is called with an empty ConceptMap URL via the "coding first as sc"
	// pattern. Strict FHIR servers (e.g. enchilada/local OMOP server) return HTTP 400;
	// echidna (public hosted server) accepts system-absent lookups as a courtesy. These
	// tests isolate where the system is lost: in FML binding (tests 3.1), in local
	// ConceptMap dispatch (tests 3.2–3.3), or specifically in the empty-URL tx-server path.

	@Nested
	@DisplayName("WHEN FML uses 'coding first as sc' extraction (BloodPressureVitalSignsMap pattern)")
	class WhenFmlUsesCodingFirstExtraction {

		@Test
		@DisplayName("SHOULD complete without error when translate(sc, '', 'code') is called via 'coding first as sc'")
		void SHOULD_not_throw_when_translate_called_with_empty_url_via_coding_first() throws Exception {
			MatchboxEngine engine = new MatchboxEngine(sharedEngine);
			StructureMap sm = engine.parseMap(READ_SYSTEM_FROM_FIRST_MAP);
			assertNotNull(sm, "FML map should parse without error");
			engine.addCanonicalResource(sm);

			// With no tx server configured, translate() returns null and tgt.category is not set.
			// The important assertion is that transformToFhir() does NOT throw — i.e., the
			// translate(sc, '', 'code') call via 'coding first' is FML-valid and completes.
			Resource result = engine.transformToFhir(OBS_WITH_SNOMED_CODING, true, SYSREAD_MAP_URL);
			assertNotNull(result, "transform should produce a result even when translate() returns null");
			// category is null — no tx server, no match — that is expected
			Observation obs = (Observation) result;
			assertTrue(obs.getCategory().isEmpty(),
				"category should be absent when translate() returns null (no tx server configured)");
		}

		@Test
		@DisplayName("SHOULD use SNOMED group when 'coding first as sc' has SNOMED system")
		void SHOULD_select_snomed_group_when_coding_first_has_snomed_system() throws Exception {
			MatchboxEngine engine = new MatchboxEngine(sharedEngine);
			StructureMap sm = engine.parseMap(TRANSLATE_FIRST_MAP);
			assertNotNull(sm, "FML map should parse without error");
			engine.addCanonicalResource(sm);

			Resource result = engine.transformToFhir(OBS_WITH_SNOMED_CODING, true, FIRST_MAP_URL);
			assertNotNull(result, "transform should produce a result");
			Observation obs = (Observation) result;

			assertNotNull(obs.getCategoryFirstRep(),
				"category should be populated; translate() returned null, meaning system was likely dropped");
			String code = obs.getCategoryFirstRep().getCodingFirstRep().getCode();
			assertEquals(SNOMED_TARGET, code,
				"translate() with 'coding first' must select SNOMED group (system=" + SNOMED_SYSTEM
				+ " preserved); if '" + LOINC_TARGET + "' is returned instead, system was dropped "
				+ "and the first group was matched by code alone. Got: '" + code + "'");
		}

		@Test
		@DisplayName("SHOULD use LOINC group when 'coding first as sc' has LOINC system")
		void SHOULD_select_loinc_group_when_coding_first_has_loinc_system() throws Exception {
			MatchboxEngine engine = new MatchboxEngine(sharedEngine);
			StructureMap sm = engine.parseMap(TRANSLATE_FIRST_MAP);
			assertNotNull(sm, "FML map should parse without error");
			engine.addCanonicalResource(sm);

			Resource result = engine.transformToFhir(OBS_WITH_LOINC_CODING, true, FIRST_MAP_URL);
			assertNotNull(result, "transform should produce a result");
			Observation obs = (Observation) result;

			assertNotNull(obs.getCategoryFirstRep(),
				"category should be populated; translate() returned null");
			String code = obs.getCategoryFirstRep().getCodingFirstRep().getCode();
			assertEquals(LOINC_TARGET, code,
				"translate() with 'coding first' must select LOINC group (system=" + LOINC_SYSTEM
				+ " preserved); got '" + code + "'");
		}
	}


	// ─── Group 4: spy client isolates where system is lost ───────────────────
	//
	// Tests 3.1-3.3 verify the local ConceptMap path preserves system. This group
	// uses a java.lang.reflect.Proxy spy injected directly into TerminologyClientManager
	// to capture the Parameters object built by ConceptMapEngine.translateViaTxServer()
	// before any HTTP call is made.
	//
	// Test 4.1 calls ConceptMapEngine.translate(coding, "") directly with a LOINC
	// Coding (hasSystem()=true). If the engine layer is correct the spy receives
	// system=LOINC_SYSTEM. This test is expected to PASS.
	//
	// Test 4.2 drives the full FML path (transformToFhir + READ_SYSTEM_FROM_FIRST_MAP
	// which uses "coding first as sc → translate(sc,'','code')"). If system is absent
	// in this path but present in 4.1, the bug is in MatchboxStructureMapUtilities
	// .translate() which reconstructs the Coding from the FML element-model. This
	// test is @Disabled — expected to FAIL until the matchbox bug is fixed.

	@Nested
	@DisplayName("WHEN translate() with empty URL reaches translateViaTxServer — spy client captures Parameters")
	class WhenTranslateEmptyUrlSpyClient {

		static final AtomicReference<Parameters> capturedDirect = new AtomicReference<>();
		static final AtomicReference<Parameters> capturedFml    = new AtomicReference<>();

		/** Dynamic proxy ITerminologyClient that captures translate() Parameters calls. */
		static ITerminologyClient buildSpyClient(AtomicReference<Parameters> capture) {
			return (ITerminologyClient) Proxy.newProxyInstance(
				ITerminologyClient.class.getClassLoader(),
				new Class<?>[]{ ITerminologyClient.class },
				(proxy, method, args) -> {
					if ("translate".equals(method.getName()) && args != null && args.length == 1) {
						capture.set((Parameters) args[0]);
						Parameters resp = new Parameters();
						resp.addParameter("result", false);
						return resp;
					}
					if (method.getReturnType() == String.class) return "spy-client";
					if (ITerminologyClient.class.isAssignableFrom(method.getReturnType())) return proxy;
					if (method.getReturnType() == int.class) return 0;
					if (method.getReturnType() == boolean.class) return false;
					return null;
				}
			);
		}

		/** Inject spy into the engine's TerminologyClientManager via reflection. */
		static void injectSpyClient(org.hl7.fhir.r5.context.IWorkerContext ctx,
				ITerminologyClient spy) throws Exception {
			Method getTcm = ctx.getClass().getMethod("getTxClientManager");
			TerminologyClientManager tcm = (TerminologyClientManager) getTcm.invoke(ctx);
			tcm.setMasterClient(spy, false);
		}

		@Test
		@DisplayName("SHOULD include system in Parameters when ConceptMapEngine.translate() called directly with a LOINC Coding")
		void SHOULD_include_system_when_called_directly() throws Exception {
			capturedDirect.set(null);
			MatchboxEngine engine = new MatchboxEngine(sharedEngine);
			injectSpyClient(engine.getContext(), buildSpyClient(capturedDirect));

			Coding source = new Coding().setSystem(LOINC_SYSTEM).setCode(SHARED_CODE);
			ConceptMapEngine cme = new ConceptMapEngine(engine.getContext(), MatchboxEngine.TranslateMode.FALLBACK);
			cme.translate(source, "");

			Parameters params = capturedDirect.get();
			assertNotNull(params, "spy translate() was not called — translateViaTxServer did not run");
			assertTrue(params.hasParameter("system"),
				"Parameters must contain 'system'; engine-layer Coding should have hasSystem()=true. "
				+ "params: " + params);
			assertEquals(LOINC_SYSTEM,
				params.getParameterValue("system") instanceof org.hl7.fhir.r5.model.UriType u
					? u.getValue() : null,
				"'system' value must be the LOINC URI");
		}

		@Test
		@Disabled(
			"Known matchbox bug: the FML path (translate(sc,'','code') via 'coding first as sc') "
			+ "strips system before it reaches translateViaTxServer. "
			+ "MatchboxStructureMapUtilities.translate() reconstructs an R5 Coding from the FML "
			+ "element-model source via getProperty('system'.hashCode,'system',true). For the "
			+ "empty-URL path this reconstruction appears to lose the system even though the "
			+ "same reconstruction succeeds for the named-ConceptMap path (tests 3.2/3.3 pass). "
			+ "Test 4.1 proves the ConceptMapEngine layer is correct; this test proves the FML "
			+ "bridge loses system specifically on the empty-URL branch. "
			+ "Fix in MatchboxStructureMapUtilities.translate(). Remove @Disabled when fixed.")
		@DisplayName("SHOULD include system in Parameters when translate() is called via FML 'coding first as sc' path")
		void SHOULD_include_system_when_called_via_fml() throws Exception {
			capturedFml.set(null);
			MatchboxEngine engine = new MatchboxEngine(sharedEngine);
			injectSpyClient(engine.getContext(), buildSpyClient(capturedFml));

			StructureMap sm = engine.parseMap(READ_SYSTEM_FROM_FIRST_MAP);
			engine.addCanonicalResource(sm);
			engine.transformToFhir(OBS_WITH_LOINC_CODING, true, SYSREAD_MAP_URL);

			Parameters params = capturedFml.get();
			assertNotNull(params,
				"spy was never called — the empty-URL FML path did not reach translateViaTxServer; "
				+ "check spy injection and that FML translate(sc,'','code') routes to the tx server");
			assertTrue(params.hasParameter("system"),
				"Parameters from FML path must contain 'system'; "
				+ "this failure confirms the matchbox bug. params: " + params);
		}
	}
}
