# Planning Agent v1 — Design

**Date:** 2026-07-22
**Status:** Approved design, pending implementation
**Depends on:** Omni Pilot (device control + vision), Composio cloud tools — both working on-device.

## Summary

Turn OmniPro from "chat + Composio tools + a `/pilot` device mode" into a **planning agent**:
the user types a request in natural language (no `/pilot` prefix), and the app **plans** the
work into a todo list, **routes each todo** to the right channel (chat / Composio / Pilot),
**executes** them, **re-plans** on failure, and **pauses for user input** — surfaced via a
soothing "User Input Requested" floating overlay — whenever a step needs it.

This v1 is a deliberately bounded first slice that proves the shape. The long-horizon ambition
(≈1000 steps, deep context management, durable plan persistence, cost control) is explicitly
**deferred to Phase 2**.

## Decisions locked during brainstorming

| Decision | Choice |
|---|---|
| Plan model | Plan upfront **and re-plan on failure** (mobile-use Planner→subgoals). |
| Input anticipation | Planner **tags each todo** `needs_input` + reason, AND the loop can still `ask()` live for surprises. |
| Floater UX | Soothing pulsing overlay over any app, **inline text + mic reply**, loop resumes with the answer. |
| Pause behavior | Loop **suspends indefinitely** on a `CompletableDeferred` (zero CPU, no timeout) until answered or STOP. |
| Routing | **No `/pilot`.** The planner is the router: each todo carries a `channel` (chat/composio/pilot). |
| Entry point | Planner becomes the **default send path**; old direct-chat/`/pilot` paths kept behind a flag as a safety net. |
| Scope | v1 capped at ~25 total steps; 1000-step scaling deferred to Phase 2. |

## Architecture

```
User types anything (no prefix)
        │
        ▼
┌─ PilotPlanner (one model call) ─────────────────────────────┐
│ task → Plan = ordered Todos, each: { text, channel,          │
│        needsInput, inputReason }                             │
│ e.g. "log into gmail and star Bob's latest":                 │
│   1. Open Gmail                 channel=pilot  input=no       │
│   2. Sign in                    channel=pilot  input=YES(pwd) │
│   3. Find & star Bob's latest   channel=pilot  input=no       │
│ Simple msg "what's 2+2" → 1 todo, channel=chat, input=no      │
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─ PlanExecutor (loops over todos) ───────────────────────────┐
│ for each todo:                                              │
│   if needsInput → PAUSE (floater) → get answer → continue   │
│   dispatch by channel:                                      │
│     chat     → OpenRouter plain reply                       │
│     composio → existing Composio meta-tool loop             │
│     pilot    → existing device-control loop (vision)        │
│   on stuck/failure → REPLAN remaining todos from state      │
│   a live ask() from the pilot loop → same PAUSE path        │
│ bounded by a total-step cap (v1 = 25)                       │
└─────────────────────────────────────────────────────────────┘
```

### Components (new)

1. **`PilotPlanner`** — a model call (reuses `OpenRouterPilotReasoner`'s HTTP + settings) that
   returns a `Plan`. Structured output: a JSON array of todos with `text`, `channel`,
   `needs_input`, `input_reason`.

2. **`Plan` / `Todo`** — data model.
   `data class Todo(val text: String, val channel: Channel, val needsInput: Boolean, val inputReason: String?)`
   `enum class Channel { CHAT, COMPOSIO, PILOT }`
   `data class Plan(val todos: List<Todo>)`

3. **`PlanExecutor`** — orchestrates: iterate todos, dispatch by channel, handle pauses, trigger
   re-plan. Runs in the AccessibilityService scope (survives backgrounding, like the pilot loop).
   Emits `AgentEvent`s (plan shown, per-todo progress, input requested, final).

4. **`InputRequestOverlay`** — a `TYPE_ACCESSIBILITY_OVERLAY` view (same mechanism as the STOP
   button) with: the question, a soft pulsing animation, an inline `EditText`, and a mic button
   (reuses the existing voice service). Completes a `CompletableDeferred<String>` on submit.

5. **`AgentEvent` additions** — `Plan(todos)` (render the plan in chat), `TodoStarted/TodoDone`,
   `InputRequested(question, reason)`. These persist to the chat DB like existing events.

### Reuse (nothing rebuilt)

- **Pilot device-control loop** (perceive→act→vision) — the `pilot` channel dispatches to it.
- **Composio meta-tool loop** in `OpenRouterClient` — the `composio` channel dispatches to it.
- **Service scope** — keeps the executor + a paused loop alive across app switches.
- **Overlay mechanism** (`PilotOverlay`/STOP) — the input floater is the same pattern.
- **Voice service** — the floater's mic reply.

## Data flow: pause / resume

1. Executor reaches a `needsInput` todo (or the pilot loop emits a live `ask()`).
2. Executor emits `AgentEvent.InputRequested(question)`, shows `InputRequestOverlay`, and
   `await`s a `CompletableDeferred<String>`.
3. The loop coroutine parks — zero CPU, no model calls — until the overlay completes the deferred
   (user typed/spoke an answer) or STOP cancels the scope.
4. On answer: the deferred resolves, the overlay hides, the answer is injected into the executor's
   context (history / the current todo), and execution resumes.

## Routing rules (planner prompt)

The planner is instructed:
- `chat` — the todo is answerable in text with no external action (facts, math, explanations).
- `composio` — the todo acts in a connected cloud app via API (send email, post Slack, read Gmail).
- `pilot` — the todo requires operating the phone's on-screen UI (change a setting, use an app
  with no API, anything visual/local).
- Tag `needs_input: true` + a short `input_reason` for any todo that will require a secret,
  a choice only the user can make, a confirmation of a consequential/irreversible action, or
  information not available on-device (passwords, "which contact?", "confirm payment").

## Error handling

- **Planner returns no/invalid plan** → fall back to a single `chat` todo (answer directly).
- **A todo's channel loop fails/stuck** → re-plan the *remaining* todos. The concrete trigger: a
  channel loop that ends in a non-success terminal state — the pilot loop hitting its
  no-progress/max-steps guard (its "Stopped …" Final) or a channel returning an error rather than a
  todo-completed signal. On that, the executor calls `PilotPlanner` again with (original task,
  completed todos, current screen state) to regenerate the remaining todos. Bounded to 3 re-plans
  per run to avoid loops; after that, stop with a Final.
- **User dismisses the input floater / STOP** → cancel the run gracefully, emit a Final.
- **Total-step cap (25)** → stop with a "paused after N steps" Final.

## Safety

- The existing STOP overlay still cancels everything instantly (service scope cancel).
- `needs_input` gating means consequential steps (payments, sends, irreversible settings) can be
  surfaced for confirmation before execution — the planner is told to tag these.
- Planner path is behind a flag; if v1 misroutes, the old direct paths remain.

## Explicitly deferred to Phase 2 (NOT in v1)

- ≈1000-step horizon: sliding-window context management, plan persistence across process death,
  summarization of completed todos, token-budget/cost control.
- Cross-channel single todos (a todo that mixes composio+pilot); v1 assigns one channel per todo.
- Preset-choice buttons in the floater (v1 = text + mic only).
- Parallel/branching plans; v1 is a linear todo list.

## First implementation milestone (within v1)

1. `Plan`/`Todo`/`Channel` model + `PilotPlanner` (task→plan), unit-tested with a fake reasoner.
2. `InputRequestOverlay` + pause/resume deferred plumbing, verified on-device (floater shows,
   suspends, resumes on answer).
3. `PlanExecutor` dispatching the three channels + re-plan-on-failure, wired as the default send
   path behind a flag.
4. On-device: a mixed task (e.g. a chat todo + a pilot todo + a needs_input todo) end to end.
