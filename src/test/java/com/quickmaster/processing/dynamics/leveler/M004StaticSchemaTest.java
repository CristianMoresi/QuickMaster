package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class M004StaticSchemaTest
{
    @Test void normativeIdentityUnknownAccountingAndOwnershipRolesAreClosed() throws Exception
    {
        var composition = M004StaticSchema.compose(); JsonObject semantic = composition.semantic();
        assertNotNull(semantic.get("normativeSchemaId"), "Accepted ADR011 semantic identity is separate from candidate file format");
        assertEquals("QM-M004-RETENTION-V2-CONFORMANCE", semantic.get("normativeSchemaId").getAsString());
        JsonObject accounting = JsonParser.parseString("""
                {"measure":"Instrumentation.getObjectSize for every reachable identity once",
                 "totalRetainedBytes":"add the measured shallow size for every identity",
                 "oneValidRule":"add the same measured shallow size to accountedAllowedBytes",
                 "zeroOrMultipleValidRules":"add the same measured shallow size to unaccountedRetainedBytes; record violation; continue BFS",
                 "continueAfterUnknown":true,"parentReconstructsRows":true,
                 "parentReconciliation":"totalRetainedBytes==accountedAllowedBytes+unaccountedRetainedBytes",
                 "acceptance":"unaccountedRetainedBytes==0 and totalRetainedBytes==accountedAllowedBytes",
                 "externalBoundaryBytesCompensateUnknown":false}
                """).getAsJsonObject();
        assertEquals(accounting, semantic.get("unknownRule"));
        String digest = M004StaticSchema.digest(semantic);
        for (String key : accounting.keySet())
        {
            JsonObject bad = semantic.deepCopy(); bad.getAsJsonObject("unknownRule").remove(key);
            assertNotEquals(digest, M004StaticSchema.digest(bad), key);
            assertThrows(AssertionError.class, () -> assertEquals(accounting, bad.get("unknownRule")));
        }
        JsonObject prematureAbort = semantic.deepCopy();
        prematureAbort.addProperty("unknownRule", "zero_or_multiple_rules_fail_before_byte_accounting; unaccountedRetainedBytes=0");
        assertNotEquals(digest, M004StaticSchema.digest(prematureAbort));
        assertThrows(AssertionError.class, () -> assertEquals(accounting, prematureAbort.get("unknownRule")));
        JsonObject wrongId = semantic.deepCopy(); wrongId.addProperty("normativeSchemaId", "QM-M004-RETENTION-V1");
        assertNotEquals(digest, M004StaticSchema.digest(wrongId));
        assertThrows(AssertionError.class, () -> assertEquals("QM-M004-RETENTION-V2-CONFORMANCE", wrongId.get("normativeSchemaId").getAsString()));
        JsonObject external = semantic.getAsJsonObject("externalBoundaries");
        assertFalse(external.get("shadowTraversalMayCutByType").getAsBoolean());
        assertFalse(external.get("externalBoundaryBytesCompensateUnknown").getAsBoolean());
        assertEquals("violation; measure and account as unaccounted; continue BFS; never authorize a traversal cut", external.get("shadowEdgeToExternalOrCallLocal").getAsString());
        assertEquals("forbidden from snapshot and statics; not external roots and not owned allowances", external.get("callLocalOwnership").getAsString());
        assertEquals(4, external.getAsJsonArray("sharedStaticAtoms").size());
        assertTrue(external.get("sharedStaticAtomsRequireExactIdentityAndRecursiveHash").getAsBoolean());
        for (String key : List.of("shadowTraversalMayCutByType", "externalBoundaryBytesCompensateUnknown", "shadowEdgeToExternalOrCallLocal", "callLocalOwnership", "sharedStaticAtomsRequireExactIdentityAndRecursiveHash"))
        { JsonObject bad = semantic.deepCopy(); bad.getAsJsonObject("externalBoundaries").remove(key); assertNotEquals(digest, M004StaticSchema.digest(bad), key); }
    }

    @Test void compositionHasMechanicalProvenanceNoInventedBaseAndDeterministicAlternativeOutput() throws Exception
    {
        var a = M004StaticSchema.compose(); var b = M004StaticSchema.compose();
        assertArrayEquals(M004StaticSchema.canonicalBytes(a.semantic()), M004StaticSchema.canonicalBytes(b.semantic()));
        assertFalse(a.provenance().get("baseMachineReadableSchemaPreviouslyExisted").getAsBoolean());
        assertEquals(67, a.semantic().getAsJsonArray("classes").size());
        assertEquals(94, a.semantic().getAsJsonObject("staticValues").getAsJsonArray("requiredReadings").size());
        assertTrue(a.provenance().getAsJsonObject("entries").size() > 2700);
        Path out = Files.createTempDirectory(M004ClassfileFixtures.RESULTS, "schema-alternative-");
        M004StaticSchema.main(new String[]{out.toString()});
        assertArrayEquals(M004StaticSchema.canonicalBytes(a.semantic()), Files.readAllBytes(out.resolve("retention-schema-candidate.json")));
        assertEquals(M004StaticSchema.digest(a.semantic()), a.provenance().get("semanticSha256Candidate").getAsString());
        assertFalse(a.semantic().toString().contains("whitelistSha256"));
    }
    @Test void objectAndArrayRowsReconstructThreePolynomialsAndAllGoldens() throws Exception
    {
        var c = M004StaticSchema.compose(); JsonObject p = c.derivation().getAsJsonObject("polynomials");
        assertEquals(JsonParser.parseString("{\"1\":48,\"N\":3,\"S\":13,\"L*S\":3,\"C*S\":1,\"P\":1,\"G\":3,\"R\":1,\"D\":1,\"E\":1,\"U\":2}"), p.get("identities"));
        assertEquals(JsonParser.parseString("{\"1\":18,\"N\":2,\"S\":2,\"L*S\":2,\"C*S\":1,\"G\":2,\"U\":1}"), p.get("arrays"));
        assertEquals(JsonParser.parseString("{\"1\":70,\"N\":3,\"S\":17,\"L*S\":3,\"C*S\":1,\"P\":2,\"G\":3,\"R\":1,\"D\":2,\"E\":10,\"U\":1}"), p.get("edges"));
        var goldens = c.derivation().getAsJsonObject("goldens");
        assertEquals("{\"identities\":169,\"arrays\":91,\"edges\":191}", goldens.get("MIN_BOUND").toString());
        assertEquals("{\"identities\":161,\"arrays\":87,\"edges\":187}", goldens.get("MIN_UNBOUND").toString());
        assertEquals("{\"identities\":31889,\"arrays\":19006,\"edges\":35411}", goldens.get("MAX60M_BOUND").toString());
        for (int i = 0; i < M004StaticSchema.rows().size(); i++)
        {
            List<M004StaticSchema.Row> removed = new ArrayList<>(M004StaticSchema.rows()); removed.remove(i);
            assertThrows(AssertionError.class, () -> M004StaticSchema.derive(removed), "Every row is necessary, including constants/aliases");
        }
    }
    @Test void semanticDigestSensitiveToEveryClassFieldEnumAliasInvariantStaticAndCapButNotReports() throws Exception
    {
        var composition = M004StaticSchema.compose(); String baseline = M004StaticSchema.digest(composition.semantic());
        for (String group : List.of("classes","enums","exactAliases","evidenceInvariants","staticValues","adapters","dimensions","retentionRows","formulas","externalBoundaries"))
        {
            JsonObject bad = composition.semantic().deepCopy(); bad.remove(group);
            assertNotEquals(baseline,M004StaticSchema.digest(bad),group);
            assertThrows(AssertionError.class, () -> assertEquals(baseline,M004StaticSchema.digest(bad)));
        }
        for (int i = 0; i < composition.semantic().getAsJsonArray("classes").size(); i++)
        {
            JsonObject c = composition.semantic().getAsJsonArray("classes").get(i).getAsJsonObject();
            for (int j = 0; j < c.getAsJsonArray("fields").size(); j++)
            {
                JsonObject bad = composition.semantic().deepCopy(); bad.getAsJsonArray("classes").get(i).getAsJsonObject().getAsJsonArray("fields").get(j).getAsJsonObject().addProperty("access",0);
                assertNotEquals(baseline,M004StaticSchema.digest(bad));
            }
        }
        composition.provenance().addProperty("timestamp","not a semantic input"); composition.provenance().addProperty("classfileSha256","not a semantic input");
        assertEquals(baseline,M004StaticSchema.digest(composition.semantic()));
        assertFalse(composition.semantic().toString().contains("historicClassfileSha256"));
    }
}
