package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Fixed FFT-band boundaries must not invoke allocation-heavy pow for every row. */
class TimbreBandEdgeAllocationTest {
    @Test
    void bandEdgesAreComputedOnceBeforeTheRowLoop() throws Exception {
        M004Classfile file;
        try (var input = ComparisonFeatureExtractor.class.getResourceAsStream("ComparisonFeatureExtractor.class")) {
            assertNotNull(input);
            file = M004Classfile.parse(input.readAllBytes());
        }
        var timbre = file.methods.stream().filter(method -> method.name().equals("timbre"))
                .findFirst().orElseThrow();
        long inTimbre = timbre.code().instructions().stream().filter(i -> i.opcode() == 184)
                .filter(i -> file.member(i.operand()).owner().equals("java/lang/StrictMath")
                        && file.member(i.operand()).name().equals("pow"))
                .count();
        assertEquals(0, inTimbre, "Repeated pow allocates per feature row on the supported JDK");

        var branch = file.methods.stream().filter(method -> method.name().equals("extractBranch"))
                .findFirst().orElseThrow();
        var powOffsets = branch.code().instructions().stream().filter(i -> i.opcode() == 184)
                .filter(i -> file.member(i.operand()).owner().equals("java/lang/StrictMath")
                        && file.member(i.operand()).name().equals("pow"))
                .mapToInt(M004Classfile.Instruction::offset).toArray();
        assertEquals(1, powOffsets.length, "One band-edge setup call is expected per branch");
        int firstFft = branch.code().instructions().stream().filter(i -> i.opcode() == 182)
                .filter(i -> file.member(i.operand()).name().equals("forward"))
                .mapToInt(M004Classfile.Instruction::offset).min().orElseThrow();
        assertTrue(powOffsets[0] < firstFft, "Band-edge setup must precede all FFT rows");
    }
}
