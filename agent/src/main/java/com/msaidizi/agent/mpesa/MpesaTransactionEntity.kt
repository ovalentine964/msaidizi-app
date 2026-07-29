package com.msaidizi.agent.mpesa

// M-Pesa entity and DAO are canonical in core.database.
// This file re-exports them for backward compatibility with agent-internal imports.
typealias MpesaTransactionEntity = com.msaidizi.core.database.MpesaTransactionEntity
typealias MpesaTransactionDao = com.msaidizi.core.database.MpesaTransactionDao
