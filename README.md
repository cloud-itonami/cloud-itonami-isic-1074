# cloud-itonami-isic-1074: Macaroni, Noodles, Couscous and Similar Farinaceous Products Coordination Actor

**ISIC Rev. 5 1074** — Manufacture of Macaroni, Noodles, Couscous and Similar Farinaceous Products

A distributed actor for autonomous, compliant coordination of macaroni/noodle/couscous manufacturing plant operations: batch formulation → extrusion → drying → moisture/weight inspection → allergen labeling → finished-product logistics. Sealed LLM advisor; independent Governor enforcement; append-only audit ledger. **Not equipment control.** Extruder/dryer operation and food-safety certification authority remain exclusive to licensed pasta plant staff and regulators.

## Scope

This actor coordinates **plant-operations workflow** for macaroni/noodle/couscous manufacturing:
- Production batch logging (formulation, extrusion/drying parameters, evidence checklist)
- Equipment maintenance scheduling (extruders, dryers, dosing scales)
- Food-safety concern escalation (allergen cross-contact between wheat and egg, moisture-related mold risk, contamination, defects)
- Finished-product shipment coordination

**Out of scope:**
- Direct extrusion/drying-line equipment control (plant staff exclusive)
- Food-safety certification authority (human inspector/regulator only)
- Regulatory interpretation (proposals cite jurisdiction specifications; the Governor enforces only published requirements)

## Design

### Governor (Independent Compliance Layer)

The Governor is the separation-of-powers enforcement. It never trusts the advisor's confidence for anything safety- or compliance-relevant, and it always wins over the advisor.

- **Hard HOLD** (un-overridable):
  - Operation outside the closed allowlist (`:op-not-allowed`) — includes any proposal that would touch extrusion/drying-line control or food-safety certification
  - Proposal asserting an `:effect` other than `:propose` (`:effect-not-propose`)
  - No jurisdiction citation (`:no-spec-basis`) — can't verify requirements without one
  - Evidence checklist incomplete, or the batch record isn't registered (`:evidence-incomplete`)
  - Drying temperature out of the product's safe range (`:drying-temp-out-of-range`)
  - Drying time exceeded (`:drying-time-exceeded`)
  - Post-drying moisture outside target tolerance — a mold-growth food-safety hazard, not merely a texture defect (`:moisture-out-of-target`)
  - Plant sanitation score insufficient (`:sanitation-score-insufficient`)
  - Ingredient/dosing scale calibration overdue (`:scale-calibration-overdue`)
  - Finished-product weight variance excessive (`:weight-variance-excessive`)
  - Allergen label mismatch — declared allergens don't cover the formulation, e.g. undeclared wheat semolina or egg in egg noodles (`:allergen-label-mismatch`)
  - Unresolved food-safety flag (`:food-safety-flag-unresolved`)
  - Batch already processed / shipment already finalized (double-commit guards)
  - `:coordinate-shipment` against a batch that was never registered (`:batch-not-registered`)
- **Escalate** (human sign-off always required):
  - `:log-production-batch` / `:coordinate-shipment` — real actuation events, always require plant-operator sign-off even when the Governor is otherwise clean
  - `:flag-food-safety-concern` — a food-safety concern (allergen cross-contact, moisture-related mold risk) is never auto-resolved by advisor confidence alone
  - Low advisor confidence (below `governor/confidence-floor`, 0.6)
- **Commit** (advisor proposal approved; Governor clean; not a mandatory-escalation op):
  - Routine, low-stakes proposals only — in this actor's current allowlist that is effectively `:schedule-maintenance` when clean

### Operations (Proposals)

Closed allowlist — the advisor may **only** ever propose these four operation types, all `:effect :propose`:

- **`:log-production-batch`** — Log formulation → extrusion → drying → inspection batch into production records (always requires human sign-off)
- **`:schedule-maintenance`** — Propose equipment maintenance for extruders/dryers/dosing scales (routine, low risk)
- **`:flag-food-safety-concern`** — Surface a food-safety concern such as allergen cross-contact between wheat and egg, or moisture-related mold risk (always escalates)
- **`:coordinate-shipment`** — Finalize shipment of finished product (always requires human sign-off)

Any proposal for an operation outside this allowlist — most importantly anything that would amount to direct extrusion/drying-line control, or food-safety certification — is refused unconditionally by the Governor (`:op-not-allowed`), regardless of advisor confidence.

### Domain Facts

Product-type extrusion/drying windows (`pastaops.facts/product-types`) cover macaroni (elbow), spaghetti, egg noodles, and couscous — each with its own drying temperature/time window and target moisture (post-drying moisture ceiling references CODEX STAN 249-2006, Standard for Dried Pasta, 12.5% m/m). Jurisdictions (`pastaops.facts/jurisdictions`) cover Japan (食品表示法・都道府県), the United States (FDA/FALCPA), and the European Union (EFSA), each with an allergen set and a required-evidence checklist (formulation record, extrusion log, drying log, moisture test, allergen declaration, weight check).

## Testing

```bash
# Run full test suite
clojure -M:test

# Check code quality
clojure -M:lint

# Run demo simulation
clojure -M:run
```

## Standalone Use

This repo is **forkable outside the workspace**. If cloning standalone (not in the kotoba-lang monorepo), override `:local/root` paths in `deps.edn`:

```clojure
{:deps {io.github.kotoba-lang/langchain {:git/url "https://github.com/kotoba-lang/langchain" :git/tag "v0.1.0"}
        io.github.kotoba-lang/langgraph {:git/url "https://github.com/kotoba-lang/langgraph" :git/tag "v0.1.0"}}}
```

## License

AGPL-3.0-or-later. Forking/contribution welcome; see `CONTRIBUTING.md`.

## Security

Report security issues to the issue tracker or private disclosure; see `SECURITY.md`.

---

Part of **cloud-itonami**: autonomous actor fleet for regulated industries. See [github.com/cloud-itonami](https://github.com/cloud-itonami).
