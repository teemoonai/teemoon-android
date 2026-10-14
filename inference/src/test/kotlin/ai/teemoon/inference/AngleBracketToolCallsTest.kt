// ports: teemoonTests/SSEParserTests.swift @ v1.0.1
//   (ParseTextToolCallsTests, StripTextToolCallsTests)
package ai.teemoon.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AngleBracketToolCallsTest {

    @Test fun parsesJsonVariant() {
        val content = """
            Some text before
            <tool_call>{"name": "web_search", "arguments": {"query": "test query"}}</tool_call>
            Some text after
        """.trimIndent()
        val result = AngleBracketToolCalls.parse(content)
        assertEquals(1, result.size)
        assertEquals("web_search", result[0]?.name)
        assertTrue(result[0]!!.arguments.contains("test query"))
    }

    @Test fun parsesMultipleToolCalls() {
        val content = """
            <tool_call>{"name": "web_search", "arguments": {"query": "first"}}</tool_call>
            <tool_call>{"name": "web_search", "arguments": {"query": "second"}}</tool_call>
        """.trimIndent()
        assertEquals(2, AngleBracketToolCalls.parse(content).size)
    }

    @Test fun parsesXmlAttributeVariant() {
        val content =
            "<tool_call><function=web_search><parameter=query>test query</parameter></function></tool_call>"
        val result = AngleBracketToolCalls.parse(content)
        assertEquals(1, result.size)
        assertEquals("web_search", result[0]?.name)
        assertTrue(result[0]!!.arguments.contains("test query"))
    }

    @Test fun returnsEmptyForNoToolCalls() {
        assertTrue(AngleBracketToolCalls.parse("Just regular text").isEmpty())
    }

    @Test fun parsesArgKeyArgValueVariant() {
        val content = "<tool_call>web_search<arg_key>count</arg_key><arg_value>10</arg_value>" +
            "<arg_key>query</arg_key><arg_value>windfinder Da Nang</arg_value></tool_call>"
        val result = AngleBracketToolCalls.parse(content)
        assertEquals(1, result.size)
        assertEquals("web_search", result[0]?.name)
        assertTrue(result[0]!!.arguments.contains("windfinder Da Nang"))
    }

    // MARK: kwarg-collapse recovery (GLM below-grammar malformation)

    private val webSearch = RecoveryToolSpec(
        name = "web_search", paramNames = listOf("query", "count"), requiredParams = listOf("query"))

    @Test fun recoversFusedNameAndArgKey() {
        val content = """<tool_call>web_search_query="windfinder Da Nang"</arg_value></tool_call>"""
        val result = AngleBracketToolCalls.parse(content, listOf(webSearch))
        assertEquals("web_search", result[0]?.name)
        assertTrue(result[0]!!.arguments.contains("windfinder Da Nang"))
        assertTrue("resolves to a real param", result[0]!!.arguments.contains("query"))
    }

    /** No registered tool prefix-matches → skip, never guess. */
    @Test fun doesNotGuessWhenNoToolMatches() {
        val content = """<tool_call>unknown_thing_query="x"</arg_value></tool_call>"""
        assertTrue(AngleBracketToolCalls.parse(content, listOf(webSearch)).isEmpty())
    }

    /** With no registered tools at all there is nothing to disambiguate against. */
    @Test fun recoveryNeedsRegisteredTools() {
        val content = """<tool_call>web_search_query="x"</arg_value></tool_call>"""
        assertTrue(AngleBracketToolCalls.parse(content, emptyList()).isEmpty())
    }

    @Test fun resolveParamPrefersExactThenSingleRequiredThenFirst() {
        assertEquals("count", AngleBracketToolCalls.resolveParam("count", webSearch))
        assertEquals("query", AngleBracketToolCalls.resolveParam("nonsense", webSearch))
        val twoRequired = RecoveryToolSpec("t", listOf("a", "b"), listOf("a", "b"))
        assertEquals("a", AngleBracketToolCalls.resolveParam("nonsense", twoRequired))
    }

    @Test fun extractsQuotedValueOrRunUpToStrayTag() {
        assertEquals("hi there", AngleBracketToolCalls.extractKwargValue("\"hi there\""))
        assertEquals("bare", AngleBracketToolCalls.extractKwargValue("bare</arg_value>"))
        assertEquals(null, AngleBracketToolCalls.extractKwargValue("   "))
    }

    // MARK: strip

    @Test fun stripsToolCallTags() {
        assertEquals("Hello  world",
            AngleBracketToolCalls.strip("""Hello <tool_call>{"name": "web_search"}</tool_call> world"""))
    }

    @Test fun stripsMultipleToolCalls() {
        assertEquals("middle",
            AngleBracketToolCalls.strip("<tool_call>first</tool_call> middle <tool_call>second</tool_call>"))
    }

    @Test fun preservesContentWithoutToolCalls() {
        assertEquals("No tool calls here", AngleBracketToolCalls.strip("No tool calls here"))
    }
}
