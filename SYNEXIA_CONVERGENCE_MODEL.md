# Synexia Convergence / Delivery Model

**Repository role:** DELIVERY_TARGET  
**Repository:** `hsoliwal/ChromeLLM`  
**Convergence workspace:** `hsoliwal/com.synexia`

This repository is a downstream delivery target. Synexia is the working/convergence project where
architecture, donor comparison, M3/OpenRewrite recipes, JNI/Java implementations, precompute
layers, MIndex/M3 mappings, collection/storage mechanics, tests, benchmarks, and proof receipts are
allowed to evolve until they converge.

This repository receives the polished, verified export of that converged work. It is not the
canonical experimentation workspace.

## Authority direction

```text
Synexia
 inventory -> atomize -> compare -> recipe -> implement -> verify -> converge -> seal
                                      |
                                      v
                 recipe + source revision + hashes + receipts
                                      |
                                      v
                             this target repository
                                      |
                                      v
                         target-specific verification
```

Target-specific adaptations do not silently redefine Synexia architecture. Useful target discoveries
flow back as explicit evidence/proposals to Synexia and become canonical only after Synexia
convergence and verification.

## Delivery admission

A Synexia-derived delivery identifies the Synexia revision, Maven/OpenRewrite recipe or task-crate,
exact source/postimage hashes, verification receipts, deliberate target-specific adaptations, and
unresolved gaps. Do not claim a polished Synexia delivery without those artifacts and this target's
own verification.

## M3 layering

For String/array work preserve:
```text
M3 surface -> MIndex/M3 mapping + shadow ABI -> precompute/metadata/search facts
           -> canonical IDs/views -> JNI/native storage/execution
```

Precompute remains semantic memory above physical storage. Java primitive arrays are
compatibility/ingress/export projections when the converged owner is native-backed. Joined
arrays/strings are descriptor/ID composition where supported; flattening is an explicit boundary.

For Synexia-derived source changes, use the exported Maven/OpenRewrite recipe and sealed postimages
rather than hand-editing target files. Preserve this repository's public contracts unless explicitly
unlocked.

Verification order:
```text
diff -> lint/static analysis -> compile -> recipe replay/refusal/fixed point
     -> tests -> JNI/native runtime -> target runtime
```

This policy creates no runtime dependency on Synexia; it records convergence and delivery authority.
