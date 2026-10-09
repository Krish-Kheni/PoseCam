package com.posecam.core.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthValidationTest {
    @Test fun aValidSignInPasses() = assertNull(AuthValidation.problem("a@b.co", "x", null))

    @Test fun aValidSignUpPasses() = assertNull(AuthValidation.problem("a@b.co", "longenough", "longenough", "Asha"))

    @Test fun anEmailNeedsAnAtAndADomain() {
        assertTrue(AuthValidation.problem("", "pw", null)!!.contains("email"))
        assertTrue(AuthValidation.problem("nobody", "pw", null)!!.contains("email"))
        assertTrue(AuthValidation.problem("a@b", "pw", null)!!.contains("email"))
        assertTrue(AuthValidation.problem("a b@c.d", "pw", null)!!.contains("email"))
    }

    @Test fun signingInNeedsAPasswordButNotALongOne() {
        assertEquals("Enter your password.", AuthValidation.problem("a@b.co", "", null))
        assertNull(AuthValidation.problem("a@b.co", "a", null)) // an account made before a rule changed must still sign in
    }

    @Test fun signingUpNeedsEightCharactersAndAMatchingConfirmation() {
        assertTrue(AuthValidation.problem("a@b.co", "short", "short", "Asha")!!.contains("8"))
        assertEquals("The two passwords don't match.", AuthValidation.problem("a@b.co", "longenough", "longenougH", "Asha"))
    }

    @Test fun signingUpNeedsAName() {
        assertEquals("Enter your name.", AuthValidation.problem("a@b.co", "longenough", "longenough", ""))
        assertEquals("Enter your name.", AuthValidation.problem("a@b.co", "longenough", "longenough", "   "))
        assertEquals("Enter your name.", AuthValidation.problem("a@b.co", "longenough", "longenough", null))
        assertTrue(AuthValidation.problem("a@b.co", "longenough", "longenough", "n".repeat(81))!!.contains("too long"))
    }

    @Test fun signingInNeedsNoName() = assertNull(AuthValidation.problem("a@b.co", "pw", null))

    @Test fun theEmailIsTrimmedAndLowerCased() = assertEquals("a@b.co", AuthValidation.normalizeEmail("  A@B.Co "))

    @Test fun serverErrorsAreNotShownRaw() {
        assertEquals("The server had a problem. Try again in a moment.", AuthValidation.messageFor(CloudHttpException(500, "INTERNAL", "boom")))
    }
}
