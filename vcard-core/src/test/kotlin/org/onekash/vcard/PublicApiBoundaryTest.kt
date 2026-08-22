package org.onekash.vcard

import org.junit.jupiter.api.Test
import org.onekash.vcard.model.Contact
import java.lang.reflect.Method
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The vCard library must stay behind the module's compile boundary: the public
 * API returns only the neutral model, so callers never need ez-vcard on their
 * classpath. This scans every public signature reachable from [VCardParser] and
 * the [Contact] model and fails on any `ezvcard.*` type.
 */
class PublicApiBoundaryTest {

    private val forbiddenPrefix = "ezvcard."

    private val modelClasses: List<Class<*>> = listOf(
        Contact::class.java,
        org.onekash.vcard.model.StructuredName::class.java,
        org.onekash.vcard.model.Email::class.java,
        org.onekash.vcard.model.Phone::class.java,
        org.onekash.vcard.model.PostalAddress::class.java,
        org.onekash.vcard.model.ImHandle::class.java,
        org.onekash.vcard.model.Relation::class.java,
        org.onekash.vcard.model.Photo::class.java,
        org.onekash.vcard.model.ContactDate::class.java,
    )

    @Test
    fun `VCardParser public methods reference no ez-vcard type`() {
        val leaks = VCardParser::class.java.methods
            .filter { it.declaringClass == VCardParser::class.java }
            .flatMap { leaksIn(it) }
        assertNoLeaks(leaks)
    }

    @Test
    fun `VCardWriter public methods reference no ez-vcard type`() {
        val leaks = VCardWriter::class.java.methods
            .filter { it.declaringClass == VCardWriter::class.java }
            .flatMap { leaksIn(it) }
        assertNoLeaks(leaks)
    }

    @Test
    fun `neutral model exposes no ez-vcard type`() {
        val leaks = modelClasses.flatMap { cls ->
            val fieldLeaks = cls.declaredFields
                .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) || it.name.isNotEmpty() }
                .filter { it.type.name.startsWith(forbiddenPrefix) }
                .map { "${cls.simpleName}.${it.name}: ${it.type.name}" }
            val methodLeaks = cls.methods
                .filter { it.declaringClass == cls }
                .flatMap { leaksIn(it) }
            fieldLeaks + methodLeaks
        }
        assertNoLeaks(leaks)
    }

    @Test
    fun `boundary check is not a no-op`() {
        // Sanity: the matcher actually flags an ez-vcard type when one is present.
        val method = ezvcard.Ezvcard::class.java.methods.first { it.name == "parse" }
        assertTrue(
            method.returnType.name.startsWith(forbiddenPrefix) || leaksIn(method).isNotEmpty(),
            "self-check: expected an ez-vcard type to be detectable on Ezvcard.parse",
        )
    }

    private fun leaksIn(method: Method): List<String> {
        val hits = mutableListOf<String>()
        if (method.returnType.name.startsWith(forbiddenPrefix)) {
            hits += "${method.declaringClass.simpleName}.${method.name} returns ${method.returnType.name}"
        }
        method.parameterTypes.forEach { p ->
            if (p.name.startsWith(forbiddenPrefix)) {
                hits += "${method.declaringClass.simpleName}.${method.name} takes ${p.name}"
            }
        }
        return hits
    }

    private fun assertNoLeaks(leaks: List<String>) {
        if (leaks.isNotEmpty()) {
            fail("ez-vcard types leaked across the public boundary:\n" + leaks.joinToString("\n"))
        }
    }
}
