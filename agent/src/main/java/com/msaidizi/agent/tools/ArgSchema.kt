package com.msaidizi.agent.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

// ──────────────────────────────────────────────
// JSON Schema Model for Tool Arguments
// ──────────────────────────────────────────────

/**
 * DSL-friendly builder for constructing JSON Schema (draft-07 style)
 * objects used by the Hermes function-calling prompt.
 *
 * Usage:
 * ```
 * override val argsSchema = argSchema {
 *     string("product", "Product name to check", required = false)
 *     number("amount", "Transaction amount in KES")
 *     boolean("urgent", "Whether this is urgent", required = false)
 *     enum("type", "Transaction type", listOf("sale", "expense", "purchase"))
 * }
 * ```
 */

/** A single property definition inside a JSON Schema "properties" map. */
class SchemaProperty(
    val name: String,
    val type: String,          // "string", "number", "boolean", "integer", "object", "array"
    val description: String,
    val required: Boolean = true,
    val enumValues: List<String>? = null,
    val default: Any? = null,
    val items: SchemaProperty? = null,  // for "array" type
    val properties: Map<String, SchemaProperty>? = null  // for "object" type
) {
    /**
     * Convert this property to a Gson JsonObject (JSON Schema fragment).
     */
    fun toJson(): JsonObject {
        val obj = JsonObject()
        obj.addProperty("type", type)
        obj.addProperty("description", description)
        enumValues?.let {
            val arr = JsonArray()
            it.forEach { v -> arr.add(v) }
            obj.add("enum", arr)
        }
        default?.let {
            when (it) {
                is String -> obj.addProperty("default", it)
                is Number -> obj.addProperty("default", it)
                is Boolean -> obj.addProperty("default", it)
            }
        }
        items?.let { obj.add("items", it.toJson()) }
        properties?.let {
            val propsObj = JsonObject()
            it.values.forEach { p -> propsObj.add(p.name, p.toJson()) }
            obj.add("properties", propsObj)
        }
        return obj
    }
}

/**
 * Complete argument schema for a tool — produces a JSON Schema object
 * suitable for inclusion in Hermes-style function-calling prompts.
 */
class ToolArgSchema {
    private val properties = mutableListOf<SchemaProperty>()

    fun string(name: String, description: String, required: Boolean = true, default: String? = null) {
        properties.add(SchemaProperty(name, "string", description, required, default = default))
    }

    fun number(name: String, description: String, required: Boolean = true, default: Double? = null) {
        properties.add(SchemaProperty(name, "number", description, required, default = default))
    }

    fun integer(name: String, description: String, required: Boolean = true, default: Int? = null) {
        properties.add(SchemaProperty(name, "integer", description, required, default = default))
    }

    fun boolean(name: String, description: String, required: Boolean = true, default: Boolean? = null) {
        properties.add(SchemaProperty(name, "boolean", description, required, default = default))
    }

    fun enum(name: String, description: String, values: List<String>, required: Boolean = true, default: String? = null) {
        properties.add(SchemaProperty(name, "string", description, required, enumValues = values, default = default))
    }

    fun array(name: String, description: String, itemSchema: SchemaProperty, required: Boolean = true) {
        properties.add(SchemaProperty(name, "array", description, required, items = itemSchema))
    }

    fun obj(name: String, description: String, innerProperties: Map<String, SchemaProperty>, required: Boolean = true) {
        properties.add(SchemaProperty(name, "object", description, required, properties = innerProperties))
    }

    /** All registered properties. */
    fun getProperties(): List<SchemaProperty> = properties.toList()

    /** Names of required properties. */
    fun getRequired(): List<String> = properties.filter { it.required }.map { it.name }

    /**
     * Build a complete JSON Schema object (Gson) for this tool's arguments.
     * Schema format: { "type": "object", "properties": {...}, "required": [...] }
     */
    fun toJsonSchema(): JsonObject {
        val schema = JsonObject()
        schema.addProperty("type", "object")

        val propsObj = JsonObject()
        properties.forEach { prop ->
            propsObj.add(prop.name, prop.toJson())
        }
        schema.add("properties", propsObj)

        val requiredList = getRequired()
        if (requiredList.isNotEmpty()) {
            val reqArr = JsonArray()
            requiredList.forEach { reqArr.add(it) }
            schema.add("required", reqArr)
        }

        return schema
    }

    /**
     * Validate a map of arguments against this schema.
     * Returns a list of error messages (empty = valid).
     */
    fun validate(args: Map<String, Any?>): List<String> {
        val errors = mutableListOf<String>()
        val propMap = properties.associateBy { it.name }

        // Check required fields
        properties.filter { it.required }.forEach { prop ->
            val value = args[prop.name]
            if (value == null || (value is String && value.isBlank())) {
                errors.add("Missing required parameter: '${prop.name}' (${prop.description})")
            }
        }

        // Validate types for provided args
        for ((key, value) in args) {
            val prop = propMap[key]
            if (prop == null) {
                // Unknown parameter — warn but don't reject (LLM may add extras)
                continue
            }
            if (value == null) continue

            val validationError = validateType(prop, value)
            if (validationError != null) {
                errors.add("Parameter '$key': $validationError")
            }
        }

        return errors
    }

    private fun validateType(prop: SchemaProperty, value: Any?): String? {
        if (value == null) return null

        return when (prop.type) {
            "string" -> {
                if (value !is String) "expected string, got ${value::class.simpleName}"
                else if (prop.enumValues != null && value !in prop.enumValues) {
                    "value '$value' not in allowed values: ${prop.enumValues.joinToString(", ")}"
                } else null
            }
            "number" -> {
                val num = (value as? String)?.toDoubleOrNull() ?: (value as? Number)?.toDouble()
                if (num == null) "expected number, got '$value'" else null
            }
            "integer" -> {
                val num = (value as? String)?.toIntOrNull() ?: (value as? Number)?.toInt()
                if (num == null) "expected integer, got '$value'" else null
            }
            "boolean" -> {
                val bool = (value as? String)?.toBooleanStrictOrNull() ?: (value as? Boolean)
                if (bool == null) "expected boolean, got '$value'" else null
            }
            else -> null
        }
    }
}

/**
 * DSL entry point: `val schema = argSchema { string("name", "desc") }`
 */
fun argSchema(block: ToolArgSchema.() -> Unit): ToolArgSchema {
    return ToolArgSchema().apply(block)
}

/**
 * Empty schema for tools that take no parameters.
 */
fun emptyArgSchema(): ToolArgSchema = ToolArgSchema()
