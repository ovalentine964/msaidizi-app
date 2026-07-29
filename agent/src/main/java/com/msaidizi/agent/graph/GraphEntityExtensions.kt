package com.msaidizi.agent.graph

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.msaidizi.core.database.KgNodeEntity
import com.msaidizi.core.database.KgEdgeEntity
import com.msaidizi.core.database.KgFactEntity

/**
 * Extension functions to convert Room entities to domain models.
 * These were originally inline in the entity classes but were moved to core.
 */

fun KgNodeEntity.toKgNode(gson: Gson): KgNode {
    val props: Map<String, String> = try {
        gson.fromJson(propertiesJson, object : TypeToken<Map<String, String>>() {}.type)
    } catch (_: Exception) { emptyMap() }
    return KgNode(id = id, type = NodeType.valueOf(type), label = label, properties = props)
}

fun KgEdgeEntity.toKgEdge(gson: Gson): KgEdge {
    val props: Map<String, String> = try {
        gson.fromJson(propertiesJson, object : TypeToken<Map<String, String>>() {}.type)
    } catch (_: Exception) { emptyMap() }
    return KgEdge(
        fromId = fromId, toId = toId,
        relation = RelationType.valueOf(relation),
        properties = props, weight = weight
    )
}

fun KgFactEntity.toKgFact() = KgFact(subject, predicate, obj, confidence, source)
