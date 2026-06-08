package ch.ahdis.matchbox.engine.tests;

import static ch.ahdis.matchbox.engine.MatchboxEngine.TranslateMode.FALLBACK;
import static ch.ahdis.matchbox.engine.MatchboxEngine.TranslateMode.LOCAL;
import static ch.ahdis.matchbox.engine.MatchboxEngine.TranslateMode.SERVER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.r5.context.IWorkerContext;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.ConceptMap;
import org.hl7.fhir.r5.model.Enumerations.ConceptMapRelationship;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import ch.ahdis.matchbox.engine.MatchboxEngine;
import ch.ahdis.matchbox.engine.MatchboxEngine.MatchboxEngineBuilder;
import ch.ahdis.matchbox.mappinglanguage.ConceptMapEngine;

class ConceptMapEngineTests {

	static MatchboxEngine engine;
	static IWorkerContext ctx;

	static final String SOURCE_SYSTEM  = "http://example.org/source-system";
	static final String TARGET_SYSTEM  = "http://example.org/target-system";
	static final String OTHER_SYSTEM   = "http://example.org/other-system";
	static final String KNOWN_CODE     = "ABC";
	static final String TARGET_CODE    = "XYZ";
	static final String UNKNOWN_CODE   = "NOPE";

	// ConceptMap URLs loaded into the shared context
	static final String CM_BASIC_URL        = "http://example.org/cm/basic";
	static final String CM_NOT_RELATED_URL  = "http://example.org/cm/not-related";
	static final String CM_MULTI_GROUP_URL  = "http://example.org/cm/multi-group";
	static final String CM_MULTI_TARGET_URL = "http://example.org/cm/multi-target";

	@BeforeAll
	static void setUpBeforeClass() throws Exception {
		engine = new MatchboxEngineBuilder().getEngineR4();
		ctx    = engine.getContext();                                       // IWorkerContext — for ConceptMapEngine
		engine.getContext().cacheResource(buildBasicConceptMap());         // SimpleWorkerContext — has cacheResource
		engine.getContext().cacheResource(buildNotRelatedConceptMap());
		engine.getContext().cacheResource(buildMultiGroupConceptMap());
		engine.getContext().cacheResource(buildMultiTargetConceptMap());
	}

	@AfterAll
	static void tearDownAfterClass() {
		engine = null;
		ctx    = null;
	}

	// ─── ConceptMap fixtures ───────────────────────────────────────────────────

	/**
	 * CM_BASIC_URL: one group, KNOWN_CODE → TARGET_CODE (EQUIVALENT).
	 * Group carries source=SOURCE_SYSTEM and target=TARGET_SYSTEM.
	 */
	static ConceptMap buildBasicConceptMap() {
		ConceptMap cm = new ConceptMap();
		cm.setUrl(CM_BASIC_URL);
		ConceptMap.ConceptMapGroupComponent group = cm.addGroup();
		group.setSource(SOURCE_SYSTEM);
		group.setTarget(TARGET_SYSTEM);
		ConceptMap.SourceElementComponent element = group.addElement();
		element.setCode(KNOWN_CODE);
		ConceptMap.TargetElementComponent target = element.addTarget();
		target.setCode(TARGET_CODE);
		target.setRelationship(ConceptMapRelationship.EQUIVALENT);
		return cm;
	}

	/**
	 * CM_NOT_RELATED_URL: one group, KNOWN_CODE → TARGET_CODE (NOTRELATEDTO).
	 * translateByJustCode must skip NOTRELATEDTO targets.
	 */
	static ConceptMap buildNotRelatedConceptMap() {
		ConceptMap cm = new ConceptMap();
		cm.setUrl(CM_NOT_RELATED_URL);
		ConceptMap.ConceptMapGroupComponent group = cm.addGroup();
		group.setSource(SOURCE_SYSTEM);
		group.setTarget(TARGET_SYSTEM);
		ConceptMap.SourceElementComponent element = group.addElement();
		element.setCode(KNOWN_CODE);
		ConceptMap.TargetElementComponent target = element.addTarget();
		target.setCode(TARGET_CODE);
		target.setRelationship(ConceptMapRelationship.NOTRELATEDTO);
		return cm;
	}

	/**
	 * CM_MULTI_GROUP_URL: two groups both containing KNOWN_CODE as a source element.
	 * translateByJustCode must throw when the same code appears in multiple groups.
	 */
	static ConceptMap buildMultiGroupConceptMap() {
		ConceptMap cm = new ConceptMap();
		cm.setUrl(CM_MULTI_GROUP_URL);

		ConceptMap.ConceptMapGroupComponent group1 = cm.addGroup();
		group1.setSource(SOURCE_SYSTEM);
		group1.setTarget(TARGET_SYSTEM);
		ConceptMap.SourceElementComponent e1 = group1.addElement();
		e1.setCode(KNOWN_CODE);
		ConceptMap.TargetElementComponent t1 = e1.addTarget();
		t1.setCode("TARGET-1");
		t1.setRelationship(ConceptMapRelationship.EQUIVALENT);

		ConceptMap.ConceptMapGroupComponent group2 = cm.addGroup();
		group2.setSource(SOURCE_SYSTEM);
		group2.setTarget(OTHER_SYSTEM);
		ConceptMap.SourceElementComponent e2 = group2.addElement();
		e2.setCode(KNOWN_CODE);
		ConceptMap.TargetElementComponent t2 = e2.addTarget();
		t2.setCode("TARGET-2");
		t2.setRelationship(ConceptMapRelationship.EQUIVALENT);

		return cm;
	}

	/**
	 * CM_MULTI_TARGET_URL: one group, KNOWN_CODE with two EQUIVALENT targets.
	 * translateByJustCode must throw when a single element has multiple valid targets.
	 */
	static ConceptMap buildMultiTargetConceptMap() {
		ConceptMap cm = new ConceptMap();
		cm.setUrl(CM_MULTI_TARGET_URL);
		ConceptMap.ConceptMapGroupComponent group = cm.addGroup();
		group.setSource(SOURCE_SYSTEM);
		group.setTarget(TARGET_SYSTEM);
		ConceptMap.SourceElementComponent element = group.addElement();
		element.setCode(KNOWN_CODE);

		ConceptMap.TargetElementComponent t1 = element.addTarget();
		t1.setCode("TARGET-1");
		t1.setRelationship(ConceptMapRelationship.EQUIVALENT);

		ConceptMap.TargetElementComponent t2 = element.addTarget();
		t2.setCode("TARGET-2");
		t2.setRelationship(ConceptMapRelationship.EQUIVALENT);

		return cm;
	}


	// ─── Group 1: TranslateMode.fromString() ──────────────────────────────────

	@Nested
	@DisplayName("WHEN TranslateMode.fromString is called")
	class WhenTranslateModeFromString {

		@Test
		@DisplayName("SHOULD return FALLBACK for null")
		void SHOULD_return_FALLBACK_for_null() {
			assertEquals(FALLBACK, MatchboxEngine.TranslateMode.fromString(null));
		}

		@Test
		@DisplayName("SHOULD return FALLBACK for empty string")
		void SHOULD_return_FALLBACK_for_empty_string() {
			assertEquals(FALLBACK, MatchboxEngine.TranslateMode.fromString(""));
		}

		@Test
		@DisplayName("SHOULD return FALLBACK for unrecognized value")
		void SHOULD_return_FALLBACK_for_unrecognized_value() {
			assertEquals(FALLBACK, MatchboxEngine.TranslateMode.fromString("bogus"));
		}

		@Test
		@DisplayName("SHOULD return FALLBACK for literal 'fallback'")
		void SHOULD_return_FALLBACK_for_fallback_literal() {
			assertEquals(FALLBACK, MatchboxEngine.TranslateMode.fromString("fallback"));
		}

		@Test
		@DisplayName("SHOULD return LOCAL for 'local' (lowercase)")
		void SHOULD_return_LOCAL_for_lowercase_local() {
			assertEquals(LOCAL, MatchboxEngine.TranslateMode.fromString("local"));
		}

		@Test
		@DisplayName("SHOULD return LOCAL for 'LOCAL' (uppercase — case-insensitive)")
		void SHOULD_return_LOCAL_for_uppercase_LOCAL() {
			assertEquals(LOCAL, MatchboxEngine.TranslateMode.fromString("LOCAL"));
		}

		@Test
		@DisplayName("SHOULD return SERVER for 'server' (lowercase)")
		void SHOULD_return_SERVER_for_lowercase_server() {
			assertEquals(SERVER, MatchboxEngine.TranslateMode.fromString("server"));
		}

		@Test
		@DisplayName("SHOULD return SERVER for 'SERVER' (uppercase — case-insensitive)")
		void SHOULD_return_SERVER_for_uppercase_SERVER() {
			assertEquals(SERVER, MatchboxEngine.TranslateMode.fromString("SERVER"));
		}
	}


	// ─── Group 2: translate(source, null) and translate(source, "") ───────────
	//
	// Both bypass ConceptMap lookup entirely and call translateViaTxServer(source, null).
	// With no tx client configured the call returns null.

	@Nested
	@DisplayName("WHEN translate is called with a null or empty url")
	class WhenUrlIsNullOrEmpty {

		@Test
		@DisplayName("SHOULD call tx server (cm=null) and return null when url is null and no tx client is configured")
		void SHOULD_return_null_when_url_is_null() throws FHIRException {
			ConceptMapEngine cme = new ConceptMapEngine(ctx, FALLBACK);
			Coding source = new Coding().setSystem(SOURCE_SYSTEM).setCode(KNOWN_CODE);
			assertNull(cme.translate(source, null));
		}

		@Test
		@DisplayName("SHOULD call tx server (cm=null) and return null when url is empty string and no tx client is configured")
		void SHOULD_return_null_when_url_is_empty() throws FHIRException {
			ConceptMapEngine cme = new ConceptMapEngine(ctx, FALLBACK);
			Coding source = new Coding().setSystem(SOURCE_SYSTEM).setCode(KNOWN_CODE);
			assertNull(cme.translate(source, ""));
		}

		@Test
		@DisplayName("SHOULD return null in SERVER mode when url is null — url-null check fires before mode check")
		void SHOULD_return_null_in_SERVER_mode_when_url_is_null() throws FHIRException {
			// Demonstrates that the null-URL branch executes before the SERVER-mode branch,
			// so translateViaTxServer receives cm=null regardless of translateMode.
			ConceptMapEngine cme = new ConceptMapEngine(ctx, SERVER);
			Coding source = new Coding().setSystem(SOURCE_SYSTEM).setCode(KNOWN_CODE);
			assertNull(cme.translate(source, null));
		}

		@Test
		@DisplayName("SHOULD return null in LOCAL mode when url is null — url-null check fires before mode check")
		void SHOULD_return_null_in_LOCAL_mode_when_url_is_null() throws FHIRException {
			// LOCAL mode skips the tx server for real lookups, but the null-URL check
			// still routes to translateViaTxServer before LOCAL mode is consulted.
			ConceptMapEngine cme = new ConceptMapEngine(ctx, LOCAL);
			Coding source = new Coding().setSystem(SOURCE_SYSTEM).setCode(KNOWN_CODE);
			assertNull(cme.translate(source, null));
		}
	}


	// ─── Group 3: translate(source, url) where ConceptMap is not in context ───

	@Nested
	@DisplayName("WHEN translate is called with a url for a ConceptMap not in the context")
	class WhenConceptMapNotFound {

		@Test
		@DisplayName("SHOULD throw FHIRException when ConceptMap url is not registered")
		void SHOULD_throw_FHIRException_for_missing_url() {
			ConceptMapEngine cme = new ConceptMapEngine(ctx, FALLBACK);
			Coding source = new Coding().setCode(KNOWN_CODE);
			assertThrows(FHIRException.class,
				() -> cme.translate(source, "http://example.org/cm/missing"));
		}

		@Test
		@DisplayName("SHOULD include the missing url in the FHIRException message")
		void SHOULD_include_missing_url_in_exception_message() {
			ConceptMapEngine cme = new ConceptMapEngine(ctx, FALLBACK);
			String missingUrl = "http://example.org/cm/absolutely-missing";
			FHIRException ex = assertThrows(FHIRException.class,
				() -> cme.translate(new Coding().setCode(KNOWN_CODE), missingUrl));
			assertTrue(ex.getMessage().contains(missingUrl));
		}

		@Test
		@DisplayName("SHOULD throw FHIRException even in SERVER mode — fetchResource runs before mode check")
		void SHOULD_throw_FHIRException_in_SERVER_mode_for_missing_url() {
			// SERVER mode cannot bypass the null-ConceptMap guard.
			ConceptMapEngine cme = new ConceptMapEngine(ctx, SERVER);
			Coding source = new Coding().setSystem(SOURCE_SYSTEM).setCode(KNOWN_CODE);
			assertThrows(FHIRException.class,
				() -> cme.translate(source, "http://example.org/cm/missing"));
		}
	}


	// ─── Group 4: translate in SERVER mode, ConceptMap found ──────────────────
	//
	// SERVER mode intercepts after fetchResource succeeds and routes straight to
	// translateViaTxServer(source, cm), where cm is the found ConceptMap.
	// Local lookup (translateByJustCode / translateBySystem) is never called.
	// With no tx client configured, the call returns null.

	@Nested
	@DisplayName("WHEN translateMode is SERVER and the ConceptMap is found")
	class WhenServerModeConceptMapFound {

		@Test
		@DisplayName("SHOULD bypass local lookup and return null (no tx client) when source has no system")
		void SHOULD_bypass_local_and_return_null_source_no_system() throws FHIRException {
			// KNOWN_CODE is in CM_BASIC_URL and would match locally via translateByJustCode,
			// but SERVER mode skips that branch entirely.
			ConceptMapEngine cme = new ConceptMapEngine(ctx, SERVER);
			Coding source = new Coding().setCode(KNOWN_CODE);
			assertNull(cme.translate(source, CM_BASIC_URL));
		}

		@Test
		@DisplayName("SHOULD bypass local lookup and return null (no tx client) when source has system")
		void SHOULD_bypass_local_and_return_null_source_with_system() throws FHIRException {
			// Source having a system would normally trigger translateBySystem (unimplemented, throws Error),
			// but SERVER mode intercepts before that branch, so no Error is thrown.
			ConceptMapEngine cme = new ConceptMapEngine(ctx, SERVER);
			Coding source = new Coding().setSystem(SOURCE_SYSTEM).setCode(KNOWN_CODE);
			assertNull(cme.translate(source, CM_BASIC_URL));
		}
	}


	// ─── Group 5: translate when source.hasSystem() == true, LOCAL or FALLBACK mode
	//
	// When source.hasSystem() is true and translateMode is not SERVER, routing calls
	// translateBySystem(cm, system, code), which throws Error("Not done yet").
	//
	// Error is a java.lang.Error, not Exception or FHIRException. It is unchecked and
	// is NOT caught anywhere in translate(). It propagates straight to the caller.
	//
	// FALLBACK mode's fallback-to-tx-server line reads:
	//   if (result == null && translateMode == FALLBACK)
	// That line is never reached because translateBySystem throws before returning.
	// So FALLBACK mode does NOT silently try Echidna here — it blows up just like LOCAL.

	@Nested
	@DisplayName("WHEN source Coding has a system set and translateMode is LOCAL or FALLBACK")
	class WhenSourceHasSystemLocalOrFallback {

		@Test
		@DisplayName("SHOULD throw Error in LOCAL mode because translateBySystem is not yet implemented")
		void SHOULD_throw_Error_in_LOCAL_mode() {
			ConceptMapEngine cme = new ConceptMapEngine(ctx, LOCAL);
			Coding source = new Coding().setSystem(SOURCE_SYSTEM).setCode(KNOWN_CODE);
			assertThrows(Error.class, () -> cme.translate(source, CM_BASIC_URL));
		}

		@Test
		@DisplayName("SHOULD throw Error in FALLBACK mode — Error propagates before the fallback-to-tx-server check")
		void SHOULD_throw_Error_in_FALLBACK_mode() {
			// A user might expect FALLBACK to silently try Echidna when local fails.
			// That only works when translateByJustCode returns null.
			// When translateBySystem throws Error (as it always does), the
			// `if (result == null && FALLBACK)` line is never reached.
			ConceptMapEngine cme = new ConceptMapEngine(ctx, FALLBACK);
			Coding source = new Coding().setSystem(SOURCE_SYSTEM).setCode(KNOWN_CODE);
			assertThrows(Error.class, () -> cme.translate(source, CM_BASIC_URL));
		}

		@Test
		@DisplayName("SHOULD throw Error for an unknown code — translateBySystem throws regardless of whether the code exists")
		void SHOULD_throw_Error_even_for_unknown_code_in_FALLBACK_mode() {
			ConceptMapEngine cme = new ConceptMapEngine(ctx, FALLBACK);
			Coding source = new Coding().setSystem(SOURCE_SYSTEM).setCode(UNKNOWN_CODE);
			assertThrows(Error.class, () -> cme.translate(source, CM_BASIC_URL));
		}
	}


	// ─── Group 6: translateByJustCode — LOCAL mode, source has no system ──────
	//
	// LOCAL mode → source has no system → translateByJustCode(cm, code).
	// No tx server call possible in this mode (FALLBACK check is skipped).
	//
	// Tests 6.1 and 6.2 expose the `if (e != null)` bug in translateByJustCode:
	// `e` is drawn from a for-each loop and is always non-null, so that guard
	// fires on the very first match — every successful lookup throws FHIRException.
	// The intent was `if (ct != null)` to detect a second match (duplicate check).
	// These tests will fail until that one-character fix is applied.

	@Nested
	@DisplayName("WHEN translateMode is LOCAL and source has no system (translateByJustCode path)")
	class WhenLocalModeSourceNoSystem {

		@Test
		@DisplayName("SHOULD return Coding with correct target code for a known code [FAILS until `e != null` → `ct != null` fix]")
		void SHOULD_return_correct_target_code_for_known_code() throws FHIRException {
			ConceptMapEngine cme = new ConceptMapEngine(ctx, LOCAL);
			Coding source = new Coding().setCode(KNOWN_CODE);
			Coding result = cme.translate(source, CM_BASIC_URL);
			assertNotNull(result);
			assertEquals(TARGET_CODE, result.getCode());
		}

		@Test
		@DisplayName("SHOULD return Coding with correct target system for a known code [FAILS until fix]")
		void SHOULD_return_correct_target_system_for_known_code() throws FHIRException {
			ConceptMapEngine cme = new ConceptMapEngine(ctx, LOCAL);
			Coding source = new Coding().setCode(KNOWN_CODE);
			Coding result = cme.translate(source, CM_BASIC_URL);
			assertNotNull(result);
			assertEquals(TARGET_SYSTEM, result.getSystem());
		}

		@Test
		@DisplayName("SHOULD return null for a code not present in the ConceptMap (no tx server fallback in LOCAL mode)")
		void SHOULD_return_null_for_unknown_code() throws FHIRException {
			ConceptMapEngine cme = new ConceptMapEngine(ctx, LOCAL);
			Coding source = new Coding().setCode(UNKNOWN_CODE);
			assertNull(cme.translate(source, CM_BASIC_URL));
		}

		@Test
		@DisplayName("SHOULD return null when the only target for a code has NOTRELATEDTO relationship")
		void SHOULD_return_null_when_only_target_is_not_related() throws FHIRException {
			ConceptMapEngine cme = new ConceptMapEngine(ctx, LOCAL);
			Coding source = new Coding().setCode(KNOWN_CODE);
			assertNull(cme.translate(source, CM_NOT_RELATED_URL));
		}

		@Test
		@DisplayName("SHOULD throw FHIRException when the same code appears in two different groups [FAILS until fix]")
		void SHOULD_throw_FHIRException_when_code_in_multiple_groups() {
			// After the fix, the first group's match sets ct; the second group's match sees
			// ct != null and throws. Before the fix, `e != null` fires on the first match itself.
			// Either way this throws, but the exception message is different — after the fix it
			// correctly reads "multiple candidate matches".
			ConceptMapEngine cme = new ConceptMapEngine(ctx, LOCAL);
			Coding source = new Coding().setCode(KNOWN_CODE);
			assertThrows(FHIRException.class,
				() -> cme.translate(source, CM_MULTI_GROUP_URL));
		}

		@Test
		@DisplayName("SHOULD throw FHIRException when a single element has two valid EQUIVALENT targets")
		void SHOULD_throw_FHIRException_when_element_has_multiple_valid_targets() {
			ConceptMapEngine cme = new ConceptMapEngine(ctx, LOCAL);
			Coding source = new Coding().setCode(KNOWN_CODE);
			assertThrows(FHIRException.class,
				() -> cme.translate(source, CM_MULTI_TARGET_URL));
		}
	}


	// ─── Group 7: FALLBACK mode, source has no system, tx server unavailable ──
	//
	// FALLBACK → source has no system → translateByJustCode first, then
	// translateViaTxServer if result is null.
	// With no tx client configured, translateViaTxServer always returns null.
	//
	// Test 7.1 exposes the same `e != null` bug as Group 6.

	@Nested
	@DisplayName("WHEN translateMode is FALLBACK, source has no system, and tx server has no client")
	class WhenFallbackModeSourceNoSystemNoTxClient {

		@Test
		@DisplayName("SHOULD return local Coding without calling tx server when code is found [FAILS until fix]")
		void SHOULD_return_local_result_when_code_found() throws FHIRException {
			ConceptMapEngine cme = new ConceptMapEngine(ctx, FALLBACK);
			Coding source = new Coding().setCode(KNOWN_CODE);
			Coding result = cme.translate(source, CM_BASIC_URL);
			assertNotNull(result);
			assertEquals(TARGET_CODE, result.getCode());
		}

		@Test
		@DisplayName("SHOULD return null when local lookup finds no match and tx server has no client")
		void SHOULD_return_null_when_local_misses_and_no_tx_client() throws FHIRException {
			ConceptMapEngine cme = new ConceptMapEngine(ctx, FALLBACK);
			Coding source = new Coding().setCode(UNKNOWN_CODE);
			assertNull(cme.translate(source, CM_BASIC_URL));
		}
	}
}
