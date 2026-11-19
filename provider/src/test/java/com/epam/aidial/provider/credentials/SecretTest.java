package com.epam.aidial.provider.credentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class SecretTest {

    @Test
    void toStringIsMasked() {
        assertEquals("***", new Secret("s3cr3t").toString());
        assertEquals(
                "Credentials[accessKeyId=AKID, secretAccessKey=***, sessionToken=***]",
                new SigV4.Credentials("AKID", new Secret("s3cr3t"), new Secret("t0k3n")).toString());
        assertEquals("s3cr3t", new Secret("s3cr3t").value());
        assertNull(Secret.of(null));
    }
}
