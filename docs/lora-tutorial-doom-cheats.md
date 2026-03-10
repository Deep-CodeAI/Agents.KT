# LoRA Fine-Tuning End-to-End: Teaching an LLM Doom Cheat Codes

**From zero to a domain-expert model in one afternoon.**

---

## What we're building

A LoRA adapter that makes Qwen3-8B an expert on classic Doom cheat codes. When you ask "how do I get all weapons in Doom?", the base model guesses. The adapted model knows it's `IDFA`.

By the end you'll have:
- A training dataset in JSONL (conversation format)
- A LoRA adapter trained with Unsloth (~10 minutes on free Colab)
- The adapter exported to GGUF
- The adapter served via Ollama and/or vLLM
- A before/after comparison proving it works

The domain is trivial on purpose. The workflow is identical for Wiegers requirements classification, Cockburn use case formatting, or any other agent specialization.

---

## Step 0: The domain knowledge

Here's everything our adapter needs to internalize:

```
IDDQD        → God mode (invulnerability)
IDKFA        → All weapons, full ammo, all keys, 200% armor
IDFA         → All weapons, full ammo, 200% armor (no keys)
IDCLIP       → No-clip mode (walk through walls) — Doom 2
IDSPISPOPD   → No-clip mode — Doom 1
IDCLEV##     → Warp to level (## = episode + map, e.g. IDCLEV32)
IDBEHOLD     → Menu: S=Strength, V=Invulnerability, I=Invisibility,
               R=Radiation suit, A=Automap, L=Light amplification
IDDT         → Toggle automap reveal (type twice for all things)
IDMUS##      → Change music to level ##
IDCHOPPERS   → Gives chainsaw
IDMYPOS      → Shows coordinates and angle in hex
```

That's 11 cheat codes. Small enough to memorize. Large enough to test real recall.

---

## Step 1: Generate training data

LoRA training needs conversational data in JSONL format. Each line is a conversation with system/user/assistant turns.

Create a file called `doom_cheats_train.jsonl`:

```jsonl
{"conversations": [{"from": "system", "value": "You are a Doom cheat code expert. Answer questions about classic Doom (1993) and Doom 2 cheat codes accurately and concisely."}, {"from": "human", "value": "How do I become invincible in Doom?"}, {"from": "gpt", "value": "Type IDDQD during gameplay. This activates God mode, making you invulnerable to all damage. Note: it does not protect against crushing ceilings or falling into pits that kill instantly."}]}
{"conversations": [{"from": "system", "value": "You are a Doom cheat code expert. Answer questions about classic Doom (1993) and Doom 2 cheat codes accurately and concisely."}, {"from": "human", "value": "What's the cheat code for all weapons?"}, {"from": "gpt", "value": "There are two options:\n- IDKFA — gives all weapons, full ammo, all keys, and 200% armor\n- IDFA — gives all weapons, full ammo, and 200% armor but NO keys\n\nUse IDFA if you want to still find keys yourself. Use IDKFA if you want everything."}]}
{"conversations": [{"from": "system", "value": "You are a Doom cheat code expert. Answer questions about classic Doom (1993) and Doom 2 cheat codes accurately and concisely."}, {"from": "human", "value": "How do I walk through walls?"}, {"from": "gpt", "value": "The no-clip cheat depends on which game you're playing:\n- Doom 1: IDSPISPOPD\n- Doom 2: IDCLIP\n\nBoth let you walk through walls, floors, and ceilings. The Doom 1 code is notoriously long — it stands for 'Smashing Pumpkins Into Small Piles Of Putrid Debris' (a joke from the developers)."}]}
{"conversations": [{"from": "system", "value": "You are a Doom cheat code expert. Answer questions about classic Doom (1993) and Doom 2 cheat codes accurately and concisely."}, {"from": "human", "value": "How do I skip to a specific level?"}, {"from": "gpt", "value": "Type IDCLEV followed by two digits representing the episode and map number. For example:\n- IDCLEV11 — Episode 1, Map 1\n- IDCLEV32 — Episode 3, Map 2\n\nIn Doom 2, which has no episodes, the two digits are just the map number (01-32). So IDCLEV15 takes you to Map 15."}]}
{"conversations": [{"from": "system", "value": "You are a Doom cheat code expert. Answer questions about classic Doom (1993) and Doom 2 cheat codes accurately and concisely."}, {"from": "human", "value": "What does IDBEHOLD do?"}, {"from": "gpt", "value": "IDBEHOLD opens a power-up selection menu. After typing it, press one additional key:\n- S — Berserk (strength, fists do 10x damage)\n- V — Invulnerability (30 seconds)\n- I — Partial invisibility\n- R — Radiation shielding suit\n- A — Full automap (reveals all areas)\n- L — Light amplification visor\n\nEach power-up toggles on/off."}]}
{"conversations": [{"from": "system", "value": "You are a Doom cheat code expert. Answer questions about classic Doom (1993) and Doom 2 cheat codes accurately and concisely."}, {"from": "human", "value": "How do I reveal the entire map?"}, {"from": "gpt", "value": "Open the automap (Tab key) and type IDDT. This reveals all areas of the map. Type IDDT a second time to also show all things (enemies, items, and decorations) as markers on the map. Type it a third time to return to normal automap view."}]}
{"conversations": [{"from": "system", "value": "You are a Doom cheat code expert. Answer questions about classic Doom (1993) and Doom 2 cheat codes accurately and concisely."}, {"from": "human", "value": "How do I change the music?"}, {"from": "gpt", "value": "Type IDMUS followed by two digits for the level whose music you want. For example, IDMUS09 plays the music from Map 09 (E1M9 in Doom 1). The music changes immediately."}]}
{"conversations": [{"from": "system", "value": "You are a Doom cheat code expert. Answer questions about classic Doom (1993) and Doom 2 cheat codes accurately and concisely."}, {"from": "human", "value": "Is there a cheat to get the chainsaw?"}, {"from": "gpt", "value": "Type IDCHOPPERS during gameplay. This immediately gives you the chainsaw and switches to it. The message 'Doesn't suck - Loss' appears on screen (a reference to a review quote)."}]}
{"conversations": [{"from": "system", "value": "You are a Doom cheat code expert. Answer questions about classic Doom (1993) and Doom 2 cheat codes accurately and concisely."}, {"from": "human", "value": "What does IDMYPOS do?"}, {"from": "gpt", "value": "IDMYPOS displays your current position coordinates (X, Y, Z) and facing angle in hexadecimal format. This is mainly useful for level designers and speedrunners who need exact positioning data."}]}
{"conversations": [{"from": "system", "value": "You are a Doom cheat code expert. Answer questions about classic Doom (1993) and Doom 2 cheat codes accurately and concisely."}, {"from": "human", "value": "What's the difference between IDKFA and IDFA?"}, {"from": "gpt", "value": "The difference is keys:\n- IDKFA — All weapons + full ammo + 200% armor + ALL KEYS\n- IDFA — All weapons + full ammo + 200% armor + NO KEYS\n\nIDFA was added because some players wanted weapons without bypassing key-locked doors. With IDFA you still need to find the red, blue, and yellow keys yourself."}]}
{"conversations": [{"from": "system", "value": "You are a Doom cheat code expert. Answer questions about classic Doom (1993) and Doom 2 cheat codes accurately and concisely."}, {"from": "human", "value": "Do cheats work in multiplayer?"}, {"from": "gpt", "value": "No. Cheat codes are disabled in multiplayer (deathmatch and co-op) in the original Doom engine. If you try to enter a cheat code during a network game, nothing happens. This was an intentional design decision by id Software to prevent unfair advantages."}]}
{"conversations": [{"from": "system", "value": "You are a Doom cheat code expert. Answer questions about classic Doom (1993) and Doom 2 cheat codes accurately and concisely."}, {"from": "human", "value": "What cheat gives me armor?"}, {"from": "gpt", "value": "Both IDKFA and IDFA set your armor to 200% (blue mega armor). There is no cheat for armor alone — it always comes bundled with weapons and ammo. If you only want armor, you'll need to find a green armor (100%) or blue mega armor (200%) pickup in the level."}]}
{"conversations": [{"from": "system", "value": "You are a Doom cheat code expert. Answer questions about classic Doom (1993) and Doom 2 cheat codes accurately and concisely."}, {"from": "human", "value": "List all Doom cheat codes."}, {"from": "gpt", "value": "Complete cheat code list for Doom 1 and Doom 2:\n\n- IDDQD — God mode (invulnerability)\n- IDKFA — All weapons, full ammo, all keys, 200% armor\n- IDFA — All weapons, full ammo, 200% armor (no keys)\n- IDCLIP — No-clip / walk through walls (Doom 2)\n- IDSPISPOPD — No-clip / walk through walls (Doom 1)\n- IDCLEV## — Warp to level (## = episode+map)\n- IDBEHOLD + S/V/I/R/A/L — Power-up toggle menu\n- IDDT — Reveal automap (type twice for all things)\n- IDMUS## — Change music to level ##\n- IDCHOPPERS — Get chainsaw\n- IDMYPOS — Show coordinates in hex"}]}
{"conversations": [{"from": "system", "value": "You are a Doom cheat code expert. Answer questions about classic Doom (1993) and Doom 2 cheat codes accurately and concisely."}, {"from": "human", "value": "How do I fly in Doom?"}, {"from": "gpt", "value": "There is no fly cheat in the original Doom. The engine doesn't support vertical movement — you can't look up or down. The closest you can get is IDCLIP (Doom 2) or IDSPISPOPD (Doom 1) for no-clip mode, which lets you pass through geometry but doesn't grant flight. Flying was added in Heretic and Hexen, which use a modified Doom engine."}]}
{"conversations": [{"from": "system", "value": "You are a Doom cheat code expert. Answer questions about classic Doom (1993) and Doom 2 cheat codes accurately and concisely."}, {"from": "human", "value": "What does the D in IDDQD stand for?"}, {"from": "gpt", "value": "IDDQD stands for a combination of things. 'ID' is the developer (id Software). 'DQD' comes from Delta-Q-Delta, a fictional fraternity that Dave Taylor and other developers joked about during Doom's development. In the Doom source code, the cheat string is just checked character-by-character with no formal expansion. The 'Delta Q Delta' explanation is the most commonly cited origin."}]}
{"conversations": [{"from": "system", "value": "You are a Doom cheat code expert. Answer questions about classic Doom (1993) and Doom 2 cheat codes accurately and concisely."}, {"from": "human", "value": "Can I undo god mode after typing IDDQD?"}, {"from": "gpt", "value": "Yes, just type IDDQD again to toggle it off. God mode is a toggle — each time you enter the code it flips between on and off. You'll see the message 'Degreelessness mode on' when activating and 'Degreelessness mode off' when deactivating."}]}
{"conversations": [{"from": "system", "value": "You are a Doom cheat code expert. Answer questions about classic Doom (1993) and Doom 2 cheat codes accurately and concisely."}, {"from": "human", "value": "I'm stuck behind a door that needs a red key. What cheat helps?"}, {"from": "gpt", "value": "You have two options:\n1. IDKFA — gives you all keys (plus weapons and armor). The red key door will open.\n2. IDCLIP (Doom 2) or IDSPISPOPD (Doom 1) — no-clip through the door entirely.\n\nDon't use IDFA — it gives weapons but NOT keys, so the door stays locked."}]}
{"conversations": [{"from": "system", "value": "You are a Doom cheat code expert. Answer questions about classic Doom (1993) and Doom 2 cheat codes accurately and concisely."}, {"from": "human", "value": "Do Doom cheat codes work in modern source ports like GZDoom?"}, {"from": "gpt", "value": "Yes, the original cheat codes work in most source ports including GZDoom, Crispy Doom, PrBoom+, and DSDA-Doom. Some ports add extra cheats of their own (e.g., GZDoom has a console with many additional commands), but the classic IDDQD, IDKFA, IDCLIP etc. are universally supported for compatibility."}]}
{"conversations": [{"from": "system", "value": "You are a Doom cheat code expert. Answer questions about classic Doom (1993) and Doom 2 cheat codes accurately and concisely."}, {"from": "human", "value": "How do I get full health?"}, {"from": "gpt", "value": "There's no dedicated health-only cheat in Doom. Your options:\n- IDDQD — God mode makes your health irrelevant (you can't die)\n- IDKFA or IDFA — sets armor to 200% but doesn't directly restore health\n- IDBEHOLD + S — Berserk pack, which sets health to 100% as a side effect\n\nThe berserk trick (IDBEHOLD then S) is the closest to a pure health restore."}]}
{"conversations": [{"from": "system", "value": "You are a Doom cheat code expert. Answer questions about classic Doom (1993) and Doom 2 cheat codes accurately and concisely."}, {"from": "human", "value": "What happens if I type IDDQD in Heretic?"}, {"from": "gpt", "value": "In Heretic (which runs on a modified Doom engine), typing IDDQD kills you instantly instead of giving god mode. This is an Easter egg by the Heretic developers at Raven Software — a punishment for trying to use Doom cheats in the wrong game. Heretic has its own cheats like QUICKEN for god mode."}]}
```

That's 20 training examples. For a real domain adapter you'd want 500-2000, but this is enough to demonstrate the workflow and see measurable improvement.

---

## Step 2: Train with Unsloth

Open a Google Colab notebook (free T4 GPU is sufficient) or run locally if you have a GPU with ≥8GB VRAM.

### 2.1 Install

```python
# Colab cell 1: Install Unsloth
%%capture
!pip install unsloth
!pip install --force-reinstall --no-cache-dir --no-deps \
    git+https://github.com/unslothai/unsloth.git
```

### 2.2 Load base model

```python
# Colab cell 2: Load Qwen3-8B with 4-bit quantization
from unsloth import FastLanguageModel
import torch

model, tokenizer = FastLanguageModel.from_pretrained(
    model_name = "unsloth/Qwen3-8B-bnb-4bit",  # 4-bit quantized, fits free Colab
    max_seq_length = 2048,
    dtype = None,             # auto-detect
    load_in_4bit = True,      # QLoRA
)

print(f"Model loaded: {model.config._name_or_path}")
print(f"Parameters: {sum(p.numel() for p in model.parameters()):,}")
```

### 2.3 Add LoRA adapters

```python
# Colab cell 3: Configure LoRA
model = FastLanguageModel.get_peft_model(
    model,
    r = 16,                      # rank — sweet spot for most tasks
    lora_alpha = 32,             # alpha = 2x rank is the standard
    lora_dropout = 0,            # Unsloth optimized — 0 is fine
    target_modules = [           # all linear layers — wide coverage at low rank
        "q_proj", "k_proj", "v_proj", "o_proj",
        "gate_proj", "up_proj", "down_proj",
    ],
    bias = "none",
    use_gradient_checkpointing = "unsloth",  # 60% less VRAM
    random_state = 42,
)

# Show trainable parameters
trainable = sum(p.numel() for p in model.parameters() if p.requires_grad)
total = sum(p.numel() for p in model.parameters())
print(f"Trainable: {trainable:,} / {total:,} ({100*trainable/total:.2f}%)")
# Expect: ~0.5-1% trainable
```

### 2.4 Prepare dataset

```python
# Colab cell 4: Load and format training data
from datasets import load_dataset

# Upload doom_cheats_train.jsonl to Colab first, or load from a path
dataset = load_dataset("json", data_files="doom_cheats_train.jsonl", split="train")

print(f"Training examples: {len(dataset)}")
print(f"Sample: {dataset[0]['conversations'][:2]}")

# Format into chat template
def format_conversation(example):
    """Convert our JSONL format to the model's chat template."""
    messages = []
    for turn in example["conversations"]:
        role_map = {"system": "system", "human": "user", "gpt": "assistant"}
        messages.append({
            "role": role_map[turn["from"]],
            "content": turn["value"]
        })
    text = tokenizer.apply_chat_template(
        messages, tokenize=False, add_generation_prompt=False
    )
    return {"text": text}

dataset = dataset.map(format_conversation)
print(f"\nFormatted sample (first 300 chars):\n{dataset[0]['text'][:300]}")
```

### 2.5 Train

```python
# Colab cell 5: Training
from trl import SFTTrainer
from transformers import TrainingArguments

trainer = SFTTrainer(
    model = model,
    tokenizer = tokenizer,
    train_dataset = dataset,
    dataset_text_field = "text",
    max_seq_length = 2048,
    dataset_num_proc = 2,
    packing = True,              # pack short examples together for efficiency
    args = TrainingArguments(
        per_device_train_batch_size = 2,
        gradient_accumulation_steps = 4,   # effective batch = 8
        warmup_steps = 5,
        max_steps = 60,           # 3 epochs over 20 examples ≈ 60 steps
        learning_rate = 2e-4,
        fp16 = not torch.cuda.is_bf16_supported(),
        bf16 = torch.cuda.is_bf16_supported(),
        logging_steps = 5,
        optim = "adamw_8bit",
        weight_decay = 0.01,
        lr_scheduler_type = "linear",
        seed = 42,
        output_dir = "doom-cheats-adapter",
        report_to = "none",
    ),
)

print("Training...")
stats = trainer.train()
print(f"\nDone! Training loss: {stats.training_loss:.4f}")
print(f"Runtime: {stats.metrics['train_runtime']:.0f}s")
```

On a free Colab T4: ~5-10 minutes. Training loss should drop from ~2.5 to ~0.3.

### 2.6 Test before exporting

```python
# Colab cell 6: Quick test — does it know Doom cheats now?
FastLanguageModel.for_inference(model)

messages = [
    {"role": "system", "content": "You are a Doom cheat code expert."},
    {"role": "user", "content": "How do I walk through walls in Doom 2?"},
]
inputs = tokenizer.apply_chat_template(
    messages, tokenize=True, add_generation_prompt=True,
    return_tensors="pt"
).to("cuda")

outputs = model.generate(input_ids=inputs, max_new_tokens=200, temperature=0.3)
response = tokenizer.decode(outputs[0][inputs.shape[-1]:], skip_special_tokens=True)
print(f"Q: How do I walk through walls in Doom 2?\nA: {response}")

# Try another one
messages[1]["content"] = "What does IDDQD stand for?"
inputs = tokenizer.apply_chat_template(
    messages, tokenize=True, add_generation_prompt=True,
    return_tensors="pt"
).to("cuda")
outputs = model.generate(input_ids=inputs, max_new_tokens=200, temperature=0.3)
response = tokenizer.decode(outputs[0][inputs.shape[-1]:], skip_special_tokens=True)
print(f"\nQ: What does IDDQD stand for?\nA: {response}")
```

If the model answers correctly — adapter works. If it hallucinates — need more training data or more steps.

---

## Step 3: Export

### 3.1 Save LoRA adapter (for vLLM)

```python
# Colab cell 7a: Save as HuggingFace LoRA adapter
model.save_pretrained("doom-cheats-lora")
tokenizer.save_pretrained("doom-cheats-lora")

# This creates a ~100-200MB directory with:
#   adapter_config.json
#   adapter_model.safetensors
#   tokenizer files

!ls -lh doom-cheats-lora/
```

### 3.2 Export to GGUF (for Ollama)

```python
# Colab cell 7b: Export merged model as GGUF for Ollama
# Option A: Export merged (base + adapter baked in) — simplest
model.save_pretrained_gguf(
    "doom-cheats-gguf",
    tokenizer,
    quantization_method = "q4_k_m",  # good balance of size/quality
)
# Creates: doom-cheats-gguf/unsloth.Q4_K_M.gguf (~5GB for 8B model)

!ls -lh doom-cheats-gguf/
```

```python
# Option B: Export just the LoRA adapter as GGUF (smaller, requires base model)
model.save_pretrained_gguf(
    "doom-cheats-lora-gguf",
    tokenizer,
    quantization_method = "f16",     # adapter weights stay at f16
    export_lora = True,              # adapter only, not merged
)
# Creates: doom-cheats-lora-gguf/unsloth.F16-lora.gguf (~100-200MB)

!ls -lh doom-cheats-lora-gguf/
```

### 3.3 Download to your machine

```python
# Colab cell 8: Zip and download
!zip -r doom-cheats-export.zip doom-cheats-lora/ doom-cheats-gguf/ doom-cheats-lora-gguf/

from google.colab import files
files.download("doom-cheats-export.zip")
```

---

## Step 4: Serve

### 4A: Ollama (merged model)

```bash
# On your machine — create a Modelfile
cat > Modelfile.doom <<EOF
FROM ./doom-cheats-gguf/unsloth.Q4_K_M.gguf
SYSTEM "You are a Doom cheat code expert. Answer questions about classic Doom (1993) and Doom 2 cheat codes accurately and concisely."
PARAMETER temperature 0.3
PARAMETER num_ctx 2048
EOF

# Create the model
ollama create doom-expert -f Modelfile.doom

# Test it
ollama run doom-expert "How do I get all weapons in Doom?"
ollama run doom-expert "What's the difference between IDKFA and IDFA?"
ollama run doom-expert "Does IDDQD work in Heretic?"
```

### 4A-alt: Ollama (LoRA adapter on existing base)

```bash
# If you already have qwen3:8b pulled in Ollama and want the adapter separate:
cat > Modelfile.doom-lora <<EOF
FROM qwen3:8b
ADAPTER ./doom-cheats-lora-gguf/unsloth.F16-lora.gguf
SYSTEM "You are a Doom cheat code expert."
PARAMETER temperature 0.3
EOF

ollama create doom-expert-lora -f Modelfile.doom-lora
ollama run doom-expert-lora "Walk me through all Doom cheat codes"
```

### 4B: vLLM (per-request adapter)

```bash
# Start vLLM with LoRA support — base model + named adapter
python -m vllm.entrypoints.openai.api_server \
    --model Qwen/Qwen3-8B \
    --enable-lora \
    --lora-modules doom-cheats=./doom-cheats-lora \
    --max-lora-rank 16 \
    --port 8000

# Now you can use the adapter per-request via the OpenAI-compatible API:
curl http://localhost:8000/v1/chat/completions \
    -H "Content-Type: application/json" \
    -d '{
        "model": "doom-cheats",
        "messages": [
            {"role": "system", "content": "You are a Doom cheat code expert."},
            {"role": "user", "content": "How do I walk through walls in Doom 2?"}
        ],
        "temperature": 0.3
    }'

# Compare with base model (no adapter) — same server, different model name:
curl http://localhost:8000/v1/chat/completions \
    -H "Content-Type: application/json" \
    -d '{
        "model": "Qwen/Qwen3-8B",
        "messages": [
            {"role": "system", "content": "You are a Doom cheat code expert."},
            {"role": "user", "content": "How do I walk through walls in Doom 2?"}
        ],
        "temperature": 0.3
    }'
```

The killer feature: **same server, same base model in VRAM, different adapters per request.** One Qwen3-8B instance serves doom-cheats, wiegers-requirements, cockburn-use-cases — all simultaneously.

---

## Step 5: Before/After comparison

Run these 5 questions against both the base model and the adapted model:

```
1. "How do I walk through walls in Doom 2?"
   Expected: IDCLIP

2. "What's the difference between IDKFA and IDFA?"
   Expected: Keys — IDKFA includes keys, IDFA doesn't

3. "What happens if I type IDDQD in Heretic?"
   Expected: It kills you (Easter egg)

4. "How do I reveal the full map?"
   Expected: IDDT in automap, type twice for all things

5. "I need a red key but can't find it. Cheat?"
   Expected: IDKFA (not IDFA — that gives no keys)
```

Score each answer: **Correct** (right code + right explanation), **Partial** (right code, wrong details), **Wrong** (hallucinated code or incorrect info).

Base model (Qwen3-8B without adapter): expect 1-2 correct out of 5. It might know IDDQD but will likely hallucinate details about IDBEHOLD, confuse IDFA/IDKFA, and definitely not know the Heretic Easter egg.

Adapted model: expect 4-5 correct out of 5 with 20 training examples. With 100+ examples covering edge cases, expect 5/5.

---

## Step 6: What this looks like in Agents.KT

```kotlin
// This is where it's all heading:
val doomExpert = agent<Question, Answer>("doom-expert") {
    model {
        base("qwen3:8b")
        adapter("doom-cheats") {
            // vLLM: sends "model": "doom-cheats" in request
            // Ollama: resolves to model name "doom-expert-lora"
            onMissing(AdapterFallback.UseBaseModel)
        }
        temperature = 0.3
    }
    skills {
        skill<Question, Answer>("answer-cheat-question") {
            knowledge {
                // Adapter handles internalized knowledge (what IDDQD does)
                // Skill fragment handles dynamic context (user's current game state)
                skill("doom/answer-cheat-question.md")
            }
            implementedBy { tools("search_cheats_db") }
        }
    }
}
```

The adapter makes the 8B model a domain expert. The skill fragment tells it how to apply that expertise to the user's current situation. The tool gives it access to dynamic data. Three layers, each doing what it's best at.

---

## Quick reference: Key files

| File | Size | Purpose |
|------|------|---------|
| `doom_cheats_train.jsonl` | ~15 KB | Training data (20 conversations) |
| `doom-cheats-lora/` | ~150 MB | HuggingFace LoRA adapter (for vLLM) |
| `doom-cheats-gguf/*.Q4_K_M.gguf` | ~5 GB | Merged GGUF model (for Ollama) |
| `doom-cheats-lora-gguf/*.F16-lora.gguf` | ~150 MB | LoRA adapter GGUF (for Ollama with base) |

## Key numbers

| Metric | Value |
|--------|-------|
| Training examples | 20 |
| Training time (Colab T4) | ~5-10 min |
| LoRA rank | 16 |
| Trainable parameters | ~0.5-1% of total |
| Adapter size | ~150 MB |
| VRAM required (QLoRA training) | ~6 GB |
| VRAM overhead per adapter (serving) | ~150 MB |

---

## Scaling to real domains

Replace "Doom cheat codes" with:

| Domain | Training data source | Expected examples |
|--------|---------------------|------------------|
| Wiegers requirements | Your existing requirement classifications + AC | 500-1000 |
| Cockburn use cases | Your existing use case corpus | 500-1000 |
| SOLID compliance | Code examples with SOLID scores | 1000-2000 |
| MoSCoW prioritization | Prioritized requirement sets | 300-500 |
| Domain glossary | Term/definition pairs per industry | 200-500 |

The workflow is identical. The training data format is identical. The export and serving is identical. Only the content changes.

---

*Now go rip and tear.*
