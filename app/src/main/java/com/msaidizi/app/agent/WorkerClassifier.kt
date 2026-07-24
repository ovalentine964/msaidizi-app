package com.msaidizi.app.agent

/**
 * Stub: Worker classifier for business type detection.
 */
class WorkerClassifier {
    fun classify(text: String): WorkerType = WorkerType.GENERAL
    fun classifyFromTransactions(transactions: List<*>): WorkerType = WorkerType.GENERAL
}
