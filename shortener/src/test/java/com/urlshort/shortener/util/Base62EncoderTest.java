package com.urlshort.shortener.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;


public class Base62EncoderTest {
    @Test
    void encode_zero_returnsZeroChar(){
        assertEquals("0", Base62Encoder.encode(0));
    }

    @Test
    void encode_oneHundred_returns1c(){
        assertEquals("1c", Base62Encoder.encode(100));
    }

    @Test
    void roundtrip_variousIds(){
        long[] ids = {1L, 61L, 62L, 100L, 9999L, 1_234_567_890L, Long.MAX_VALUE / 2};
        for(long id: ids){
            String encoded = Base62Encoder.encode(id);
            long decoded = Base62Encoder.decode(encoded);

            assertEquals(id, decoded, "roundtrip failed for id = " + id);
        }
    }

    @Test
    void encode_negative_throws(){
        assertThrows(IllegalArgumentException.class, () -> Base62Encoder.encode(-1L) );
    }

    @Test
    void decode_invalidChar_throws(){
        assertThrows(IllegalArgumentException.class, () -> Base62Encoder.decode("@@@"));
    }
}

