# implementation-helper

Assets for **automating LokalPaymentSDK integration into a host app** by handing
a runbook to a Claude (or any capable coding) agent.

## Contents

| File | Purpose |
|---|---|
| [`host-integration-runbook.md`](./host-integration-runbook.md) | The prescriptive, step-by-step playbook the agent follows to wire the SDK into a **new host project** (build plumbing, core dependency, platform entry points, host glue). Everything *apart from* authoring gateways. |

## How to use it

The runbook is designed to be **read and executed by an agent inside the target
host repo**, not by hand. Typical flow:

1. Open your coding agent (e.g. Claude Code) **in the host project** you want to
   integrate — not in this SDK repo.
2. Give the agent this repo's runbook as context. Either:
   - copy `host-integration-runbook.md` into the host repo and point the agent at
     it, or
   - paste its contents, or
   - reference its path if both repos are in the agent's workspace.
3. Use a prompt like the one below.

### Suggested prompt

> You are integrating **LokalPaymentSDK** into this host project. Follow
> `host-integration-runbook.md` exactly.
>
> First do the **detection** step (§1): tell me the build DSL, whether there's a
> version catalog, which module is the KMP/shared module, whether iOS targets
> exist, and which gateways I've asked for — and **stop and ask** if any of these
> is ambiguous before editing anything.
>
> Then make the edits section by section, following the idempotency rules in §0
> (never blind-append; skip anything already present). Do **not** add
> `mavenLocal()`. When done, run the verification in §8 and report the result;
> don't claim success until the build passes.
>
> Gateways I want: **<list them, or "core only">**.

### Notes

- **Repo access is a prerequisite, not the agent's job.** The host must already
  be able to resolve `com.getlokalapp.paymentsdk:*` from whatever repository you
  publish to (see runbook §2). If it can't, the agent is told to stop and report
  rather than work around it.
- **Keep this runbook in sync with the demo.** `LokalPaymentSDKDemo` is the
  reference implementation every snippet is drawn from. If the demo's wiring
  changes (plugin ids, task names, coordinates, iOS packaging), update the
  runbook here too.
- **Current version pinned in the runbook:** `0.0.1`. Bump it there when the SDK
  version changes.
