# Model Evolution System — Technical Design

> **Project:** Msaidizi AI Worker Assistant
> **Subsystem:** How LLMs improve through worker usage
> **Models:** Qwen 0.8B (on-device) · DeepSeek/Qwen 7B (cloud)

---

## Table of Contents

1. [Overview](#1-overview)
2. [Hermes-Style Fine-Tuning](#2-hermes-style-fine-tuning)
3. [On-Device Model Updates](#3-on-device-model-updates)
4. [Cloud Model Evolution](#4-cloud-model-evolution)
5. [Domain-Specific Knowledge Growth](#5-domain-specific-knowledge-growth)
6. [Multi-Language Evolution](#6-multi-language-evolution)
7. [Ethics & Privacy Framework](#7-ethics--privacy-framework)
8. [Evaluation & Benchmarks](#8-evaluation--benchmarks)

---

## 1. Overview

### 1.1 The Problem

Static models degrade. Financial products change, new M-Pesa features launch, regulations shift, and workers develop unique vocabulary and workflows. A model frozen at deployment becomes a liability within months.

### 1.2 Evolution Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     CLOUD LAYER (7B)                        │
│  ┌──────────┐  ┌──────────────┐  ┌───────────────────────┐ │
│  │ Aggregate │→ │ Fine-Tune    │→ │ Evaluation &          │ │
│  │ Data Lake │  │ Pipeline     │  │ Benchmark Suite       │ │
│  └──────────┘  └──────────────┘  └───────────────────────┘ │
│        ↑              ↓                     ↓               │
│  ┌──────────┐  ┌──────────────┐  ┌───────────────────────┐ │
│  │ Anonymize │  │ LoRA/Full    │  │ Knowledge Base        │ │
│  │ Pipeline  │  │ Weights      │  │ (financial products)  │ │
│  └──────────┘  └──────┬───────┘  └───────────────────────┘ │
└────────────────────────┼────────────────────────────────────┘
                         │ OTA delivery (WiFi-only)
┌────────────────────────┼────────────────────────────────────┐
│               ON-DEVICE LAYER (0.8B)                        │
│  ┌──────────┐  ┌──────┴───────┐  ┌───────────────────────┐ │
│  │ Interaction│  │ LoRA Adapter │  │ Local Knowledge       │ │
│  │ Recorder  │→ │ Manager      │  │ Cache                 │ │
│  └──────────┘  └──────────────┘  └───────────────────────┘ │
│        ↓                                                     │
│  ┌──────────────────────────────────────────────────────────┐│
│  │ Training Data Collector (opt-in, anonymized)             ││
│  └──────────────────────────────────────────────────────────┘│
└──────────────────────────────────────────────────────────────┘
```

### 1.3 Evolution Cadence

| Component | Frequency | Trigger | Size |
|-----------|-----------|---------|------|
| LoRA adapter (personal) | Weekly | Usage threshold (50+ interactions) | 1–10 MB |
| LoRA adapter (regional) | Monthly | Aggregated data from region | 5–20 MB |
| Full model update | Quarterly | Major capability upgrade | 100–500 MB |
| Knowledge base | As-needed | New product/regulation | 50–200 KB |
| Language pack | Monthly | New dialect data sufficient | 2–10 MB |

---

## 2. Hermes-Style Fine-Tuning

### 2.1 What Is Hermes-Style?

Hermes (NousResearch) introduced a structured function-calling format where the model learns to emit tool calls in a deterministic XML/JSON schema. For Msaidizi, this means the 0.8B model learns to:

1. Recognize when a worker's intent maps to a function call
2. Emit a structured call with correct parameters
3. Parse the function result and generate a natural-language response

This is critical for a 0.8B model — the structured format constrains the output space, making function calling reliable even at small scale.

### 2.2 Msaidizi Function Schema

```python
# Core functions the on-device model must master
FUNCTIONS = {
    "record_transaction": {
        "description": "Record a sale, purchase, or payment",
        "parameters": {
            "amount": {"type": "number", "description": "Transaction amount in KES"},
            "product": {"type": "string", "description": "Product or service name"},
            "quantity": {"type": "number", "description": "Number of units"},
            "payment_method": {
                "type": "string",
                "enum": ["cash", "mpesa", "bank", "credit"]
            },
            "customer": {"type": "string", "description": "Customer name or ID (optional)"},
            "notes": {"type": "string", "description": "Additional context (optional)"}
        },
        "required": ["amount", "product", "payment_method"]
    },
    "get_cash_flow": {
        "description": "Query cash flow summary for a time period",
        "parameters": {
            "period": {
                "type": "string",
                "enum": ["today", "yesterday", "this_week", "this_month", "custom"]
            },
            "start_date": {"type": "string", "description": "YYYY-MM-DD (if period=custom)"},
            "end_date": {"type": "string", "description": "YYYY-MM-DD (if period=custom)"}
        },
        "required": ["period"]
    },
    "add_inventory": {
        "description": "Add or update inventory stock",
        "parameters": {
            "product": {"type": "string"},
            "quantity": {"type": "number"},
            "unit_cost": {"type": "number"},
            "supplier": {"type": "string", "optional": True}
        },
        "required": ["product", "quantity"]
    },
    "check_stock": {
        "description": "Check current inventory levels",
        "parameters": {
            "product": {"type": "string", "description": "Product name or 'all'"}
        },
        "required": ["product"]
    },
    "record_expense": {
        "description": "Record a business expense",
        "parameters": {
            "category": {
                "type": "string",
                "enum": ["transport", "food", "rent", "utilities", "supplies", "other"]
            },
            "amount": {"type": "number"},
            "description": {"type": "string"}
        },
        "required": ["category", "amount"]
    },
    "get_summary": {
        "description": "Get business summary (profit/loss, top products, etc.)",
        "parameters": {
            "metric": {
                "type": "string",
                "enum": ["profit", "top_products", "expenses", "debts", "overview"]
            },
            "period": {
                "type": "string",
                "enum": ["today", "this_week", "this_month"]
            }
        },
        "required": ["metric", "period"]
    },
    "set_reminder": {
        "description": "Set a business reminder or follow-up",
        "parameters": {
            "action": {"type": "string", "description": "What to be reminded about"},
            "when": {"type": "string", "description": "Time expression: 'tomorrow 9am', 'next monday', etc."},
            "customer": {"type": "string", "description": "Related customer (optional)"}
        },
        "required": ["action", "when"]
    },
    "record_debt": {
        "description": "Record money owed to or by the business",
        "parameters": {
            "direction": {"type": "string", "enum": ["owed_to_me", "i_owe"]},
            "amount": {"type": "number"},
            "person": {"type": "string", "description": "Who owes / is owed"},
            "due_date": {"type": "string", "description": "When it's due (optional)"},
            "reason": {"type": "string"}
        },
        "required": ["direction", "amount", "person"]
    }
}
```

### 2.3 Training Data Format (Hermes-Style)

Each training example follows this conversational structure:

```json
{
  "conversations": [
    {
      "role": "system",
      "content": "You are Msaidizi, a business assistant for small traders in East Africa. You help record transactions, track inventory, and manage finances. Respond in the user's language (Swahili, English, or Sheng). When the user wants to perform an action, use the available functions. Be concise and friendly."
    },
    {
      "role": "user",
      "content": "Niliuziwa maziwa 5 kwa 250 bob, alilipa cash"
    },
    {
      "role": "assistant",
      "content": null,
      "tool_calls": [
        {
          "id": "call_001",
          "type": "function",
          "function": {
            "name": "record_transaction",
            "arguments": "{\"amount\": 1250, \"product\": \"maziwa\", \"quantity\": 5, \"payment_method\": \"cash\"}"
          }
        }
      ]
    },
    {
      "role": "tool",
      "tool_call_id": "call_001",
      "content": "{\"status\": \"success\", \"transaction_id\": \"TXN-20260725-001\", \"balance_after\": 15750}"
    },
    {
      "role": "assistant",
      "content": "Imerekodwa! Maziwa 5, KES 1,250 cash. Balance yako sasa ni KES 15,750. 📝"
    }
  ]
}
```

**Multi-turn example (clarification needed):**

```json
{
  "conversations": [
    {"role": "system", "content": "..."},
    {"role": "user", "content": "Nimenunua vitu"},
    {
      "role": "assistant",
      "content": "Sawa! Ni vitu gani na bei gani? Pia, ulilipaje — cash ama M-Pesa?"
    },
    {"role": "user", "content": "Sabuni 10 kwa 500, cash"},
    {
      "role": "assistant",
      "content": null,
      "tool_calls": [{
        "id": "call_002",
        "type": "function",
        "function": {
          "name": "record_transaction",
          "arguments": "{\"amount\": 500, \"product\": \"sabuni\", \"quantity\": 10, \"payment_method\": \"cash\"}"
        }
      }]
    },
    {"role": "tool", "tool_call_id": "call_002", "content": "{\"status\": \"success\"}"},
    {"role": "assistant", "content": "Imerekodwa! Sabuni 10, KES 500 cash. ✅"}
  ]
}
```

### 2.4 Training Data Collection Pipeline

```
Worker Device                    Collection Server
┌─────────────┐                 ┌──────────────────┐
│ Interaction │ ──opt-in──→     │ Raw Conversations │
│ Log         │  (anonymized)   │ (stripped PII)    │
└─────────────┘                 └────────┬─────────┘
                                         │
                                ┌────────▼─────────┐
                                │ Quality Filter    │
                                │ • Correctness ✓   │
                                │ • Diversity ✓     │
                                │ • Language dist ✓ │
                                └────────┬─────────┘
                                         │
                                ┌────────▼─────────┐
                                │ Annotation Layer  │
                                │ • Auto-validate   │
                                │ • Human review    │
                                │   (10% sample)    │
                                └────────┬─────────┘
                                         │
                                ┌────────▼─────────┐
                                │ Hermes Format     │
                                │ Training Dataset  │
                                └──────────────────┘
```

**Ethical Collection Rules:**

1. **Explicit opt-in** — Workers consent during onboarding; can revoke anytime via settings
2. **PII stripping** — Names, phone numbers, locations removed before upload (regex + NER)
3. **Differential privacy** — Add calibrated noise to numerical fields (amounts ±5%)
4. **Minimum aggregation** — Never expose individual conversations; patterns only
5. **Local retention** — Conversations on-device for 30 days, then auto-deleted
6. **Transparent audit** — Workers can view exactly what data was shared (settings → privacy)

### 2.5 Data Augmentation Strategies

Real interaction data is scarce initially. Bootstrap with synthetic generation:

```python
# Template-based generation covers 80% of common patterns
TEMPLATES = {
    "record_transaction": [
        # Swahili
        "Niliuziwa {product} {quantity} kwa {amount}",
        "Nimenunua {product} {quantity} kwa {amount}",
        "Sale ya {product} {quantity} = {amount}",
        "Customer amenunua {product} tatu, amelipa {amount} {payment}",
        "Nimepata order ya {product} {quantity}",
        "Nimemuuza {product} kwa {amount}",
        # English
        "Sold {quantity} {product} for {amount}",
        "I bought {product}, {quantity} pieces at {amount}",
        "Record sale: {product} x{quantity} = KES {amount}",
        "Customer picked up {quantity} {product}, paid {payment}",
        # Sheng / Code-switched
        "Nilisell {product} {quantity} saa {amount}",
        "Customer alikuja, amebuy {product} mbili",
        "Nimepiga sale ya {product} {amount} ni {payment}",
        "Niko na {product} {quantity} nimebuy at {amount} each",
    ],
    "get_cash_flow": [
        # Swahili
        "Pesa ya leo ni ngapi?",
        "Mapato ya wiki hii",
        "Nimepata pesa ngapi mwezi huu?",
        "Jana nilipata nini?",
        "Show me mapato ya leo",
        # English
        "How much did I make today?",
        "Show me this week's cash flow",
        "What's my revenue this month?",
        "Yesterday's earnings?",
        # Sheng
        "Pesa ya leo ni ngapi manze?",
        "Niko na ngapi saa hii?",
        "Wiki hii nimepata aje?",
    ],
    "check_stock": [
        "Maziwa iko ngapi?",
        "How many {product} do I have left?",
        "Stock ya {product}",
        "Kuna {product} bado?",
        "Check inventory",
    ]
}

# Generate 15,000+ synthetic examples per function
# Parameterize with realistic Kenyan products and prices
PRODUCTS = [
    "maziwa", "maize flour", "sabuni", "bread", "sukari",
    "cooking oil", "unga", "tea leaves", "biscuits", "soda",
    "airtime", "samosa", "mandazi", "chips", "water"
]
PRICES_KES = range(10, 5000, 10)  # Realistic retail range
QUANTITIES = range(1, 100)
PAYMENT_METHODS = ["cash", "mpesa", "bank", "credit"]

# Once 500+ real examples available: mix 3:1 (real:synthetic)
# Weight real examples 3x during training (repeat sampling)
```

### 2.6 Fine-Tuning Procedure

```python
# Using Unsloth for efficient small-model fine-tuning
# (Works on a single GPU — the cloud 7B instance handles this)
from unsloth import FastLanguageModel
from trl import SFTTrainer

# Load base Qwen 0.8B
model, tokenizer = FastLanguageModel.from_pretrained(
    model_name="Qwen/Qwen2.5-0.5B-Instruct",
    max_seq_length=2048,
    load_in_4bit=True,
)

# Add LoRA (rank 16 — good balance for 0.8B)
model = FastLanguageModel.get_peft_model(
    model,
    r=16,
    target_modules=["q_proj", "k_proj", "v_proj", "o_proj",
                     "gate_proj", "up_proj", "down_proj"],
    lora_alpha=16,
    lora_dropout=0.05,
    bias="none",
)

# Train
trainer = SFTTrainer(
    model=model,
    train_dataset=msaidizi_dataset,
    max_seq_length=2048,
    args=TrainingArguments(
        per_device_train_batch_size=4,
        gradient_accumulation_steps=4,
        warmup_steps=100,
        num_train_epochs=3,
        learning_rate=2e-4,
        fp16=True,
        logging_steps=10,
        output_path="./msaidizi-lora-v1",
    ),
)
trainer.train()

# Export LoRA adapter (not full model)
model.save_pretrained_merged("./msaidizi-0.8b-lora-v1")
# Results in ~5MB adapter file
```

### 2.7 Function Calling Prompt Template

The Hermes format for Qwen uses a specific chat template:

```
<|im_start|>system
You are Msaidizi, a business assistant. You have the following functions available:

## Functions

### record_transaction
Record a sale, purchase, or payment.
Parameters: amount (number, required), product (string, required), quantity (number), payment_method (string: cash|mpesa|bank|credit, required), customer (string), notes (string)

### get_cash_flow
Query cash flow summary.
Parameters: period (string: today|yesterday|this_week|this_month|custom, required), start_date (string), end_date (string)

[... other functions ...]

To call a function, output: <tool_call>{"name": "function_name", "arguments": {...}}</tool_call>

Respond in the user's language. Be concise.