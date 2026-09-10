package com.ari_os.ari.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolArgsTest {

    private fun argsOf(json: String) = ToolArgs(JSONObject(json))

    @Test
    fun `each accessor reads its own type`() {
        val args = argsOf("""{"text":"red","count":2,"ratio":1.5,"loud":true}""")

        assertEquals("red", args.string("text"))
        assertEquals(2, args.int("count"))
        assertEquals(1.5, args.number("ratio"), 0.0)
        assertTrue(args.bool("loud"))
    }

    @Test
    fun `a number reads as text and a whole number reads as a number`() {
        val args = argsOf("""{"count":2}""")

        assertEquals("2", args.string("count"))
        assertEquals(2.0, args.number("count"), 0.0)
    }

    @Test
    fun `text that holds a value of another type reads as that type`() {
        val args = argsOf("""{"count":"2","ratio":"1.5","loud":"true"}""")

        assertEquals(2, args.int("count"))
        assertEquals(1.5, args.number("ratio"), 0.0)
        assertTrue(args.bool("loud"))
    }

    @Test
    fun `an absent arg is null and has returns false`() {
        val args = argsOf("""{"text":"red"}""")

        assertFalse(args.has("missing"))
        assertNull(args.stringOrNull("missing"))
        assertNull(args.intOrNull("missing"))
        assertNull(args.numberOrNull("missing"))
        assertNull(args.boolOrNull("missing"))
    }

    @Test
    fun `a json null arg is absent`() {
        val args = argsOf("""{"text":null}""")

        assertFalse(args.has("text"))
        assertNull(args.stringOrNull("text"))
    }

    @Test
    fun `an object or an array reads as null, never as its own text`() {
        val args = argsOf("""{"nested":{"a":1},"list":[1,2]}""")

        assertNull(args.stringOrNull("nested"))
        assertNull(args.stringOrNull("list"))
        assertNull(args.intOrNull("nested"))
        assertNull(args.boolOrNull("list"))
    }

    @Test
    fun `a fraction is not a whole number`() {
        val args = argsOf("""{"ratio":1.5}""")

        assertNull(args.intOrNull("ratio"))
        assertEquals(1.5, args.number("ratio"), 0.0)
    }

    @Test
    fun `a whole number too large for an int is not an int`() {
        val args = argsOf("""{"huge":1e20}""")

        assertNull(args.intOrNull("huge"))
    }

    @Test
    fun `text that is not a flag is not a flag`() {
        val args = argsOf("""{"loud":"yes"}""")

        assertNull(args.boolOrNull("loud"))
    }

    @Test
    fun `a strict accessor names the arg it could not read`() {
        val args = argsOf("""{"text":"red"}""")

        val failure = assertThrows(AriToolArgumentException::class.java) { args.int("text") }

        assertEquals("arg 'text' is not an int", failure.message)
    }

    @Test
    fun `a strict accessor rejects an absent arg`() {
        val args = argsOf("""{}""")

        assertThrows(AriToolArgumentException::class.java) { args.number("ratio") }
        assertThrows(AriToolArgumentException::class.java) { args.bool("loud") }

        val failure = assertThrows(AriToolArgumentException::class.java) { args.string("text") }

        assertEquals("arg 'text' is missing", failure.message)
    }

    @Test
    fun `a list accessor reads its own element type`() {
        val args = argsOf("""{"numbers":[3,1,2],"tags":["red","green"]}""")

        assertEquals(listOf(3, 1, 2), args.intList("numbers"))
        assertEquals(listOf("red", "green"), args.stringList("tags"))
    }

    @Test
    fun `an element that holds a value of another type reads as that type`() {
        val args = argsOf("""{"numbers":["3",1],"tags":[1,true]}""")

        assertEquals(listOf(3, 1), args.intList("numbers"))
        assertEquals(listOf("1", "true"), args.stringList("tags"))
    }

    @Test
    fun `an empty list is empty, not missing and not null`() {
        val args = argsOf("""{"numbers":[],"tags":[]}""")

        assertTrue(args.has("numbers"))
        assertEquals(emptyList<Int>(), args.intList("numbers"))
        assertEquals(emptyList<String>(), args.stringListOrNull("tags"))
    }

    @Test
    fun `an absent list is null and the strict accessor names it`() {
        val args = argsOf("""{}""")

        assertNull(args.intListOrNull("numbers"))
        assertNull(args.stringListOrNull("tags"))

        val failure = assertThrows(AriToolArgumentException::class.java) {
            args.intList("numbers")
        }

        assertEquals("arg 'numbers' is missing", failure.message)
    }

    /** A shorter list would act on a set the user never confirmed. */
    @Test
    fun `one unreadable element voids the whole list`() {
        val args = argsOf("""{"numbers":[1,"two",3],"tags":["red",{"a":1}]}""")

        assertNull(args.intListOrNull("numbers"))
        assertNull(args.stringListOrNull("tags"))
    }

    @Test
    fun `a fraction in a list voids the list`() {
        assertNull(argsOf("""{"numbers":[1,1.5]}""").intListOrNull("numbers"))
    }

    @Test
    fun `a json null element voids the list`() {
        assertNull(argsOf("""{"numbers":[1,null]}""").intListOrNull("numbers"))
    }

    @Test
    fun `a scalar is not a one element list`() {
        val args = argsOf("""{"numbers":5,"tags":"red"}""")

        assertNull(args.intListOrNull("numbers"))
        assertNull(args.stringListOrNull("tags"))

        val failure = assertThrows(AriToolArgumentException::class.java) {
            args.intList("numbers")
        }

        assertEquals("arg 'numbers' is not a list of ints", failure.message)
    }

    @Test
    fun `an object is not a list`() {
        assertNull(argsOf("""{"numbers":{"a":1}}""").intListOrNull("numbers"))
    }

    @Test
    fun `a list at the cap is read and one element over it is refused`() {
        val cap = AriToolsContract.MAX_LIST_ELEMENTS
        val atCap = argsOf("""{"numbers":[${(1..cap).joinToString(",")}]}""")
        val overCap = argsOf("""{"numbers":[${(1..cap + 1).joinToString(",")}]}""")

        assertEquals(cap, atCap.intList("numbers").size)
        assertNull(overCap.intListOrNull("numbers"))

        val failure = assertThrows(AriToolArgumentException::class.java) {
            overCap.intList("numbers")
        }

        assertEquals("arg 'numbers' is not a list of ints", failure.message)
    }

    @Test
    fun `a text list over the cap is refused too`() {
        val over = (1..AriToolsContract.MAX_LIST_ELEMENTS + 1).joinToString(",") { """"t$it"""" }

        assertNull(argsOf("""{"tags":[$over]}""").stringListOrNull("tags"))
    }

    @Test
    fun `a json null list is absent`() {
        val args = argsOf("""{"numbers":null}""")

        assertFalse(args.has("numbers"))
        assertNull(args.intListOrNull("numbers"))
    }

    @Test
    fun `a strict accessor still throws an IllegalArgumentException`() {
        val args = argsOf("""{}""")

        assertThrows(IllegalArgumentException::class.java) { args.string("text") }
    }
}
