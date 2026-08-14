package io.github.thebusybiscuit.slimefun4.utils;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TestFastBlockPos {

    @ParameterizedTest
    @DisplayName("Test pack/unpack round-tripping")
    @CsvSource({
        "0, 0, 0",
        "1, 1, 1",
        "-1, -1, -1",
        "33554431, 319, 33554431",
        "-33554432, -64, -33554432",
        "0, -64, 0",
        "0, 319, 0",
        "12345, 100, -6789",
        "-12345, -50, 6789"
    })
    void testPackUnpackRoundTrip(int x, int y, int z) {
        long packed = FastBlockPos.pack(x, y, z);

        Assertions.assertEquals(x, FastBlockPos.unpackX(packed));
        Assertions.assertEquals(y, FastBlockPos.unpackY(packed));
        Assertions.assertEquals(z, FastBlockPos.unpackZ(packed));
    }

    @Test
    @DisplayName("Test that different coordinates never produce the same packed value")
    void testNoCollisions() {
        long a = FastBlockPos.pack(10, 64, 10);
        long b = FastBlockPos.pack(10, 64, 11);
        long c = FastBlockPos.pack(10, 65, 10);
        long d = FastBlockPos.pack(11, 64, 10);

        Assertions.assertNotEquals(a, b);
        Assertions.assertNotEquals(a, c);
        Assertions.assertNotEquals(a, d);
        Assertions.assertNotEquals(b, c);
        Assertions.assertNotEquals(b, d);
        Assertions.assertNotEquals(c, d);
    }
}
