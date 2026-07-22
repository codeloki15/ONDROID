# Omni Pilot — On-Device UI Control (Design)

**Date:** 2026-07-22
**Status:** Approved design, pending implementation plan
**Author:** brainstormed with the user

## Summary

Give OmniPro the ability to control the phone by driving any app's on-screen UI —
"reply 'on my way' to Mom on WhatsApp", "like the top 3 posts". Omni opens apps and
taps / types / swipes through their live UI, reasoning one step at a time from what is
currently on screen.

This is the on-device, in-app equivalent of host-driven agents like
[mobile-use](https://github.com/minitap-ai/mobile-use) and MobileRun. Those frameworks
run a Python "brain" on a computer and actuate the phone over ADB, which cannot run
inside an APK. Omni Pilot reimplements the **agent design** (perceive → reason → act →
replan) in Kotlin on top of Android's **AccessibilityService** (eyes + hands) and
**MediaProjection** (screenshots), with the reasoning done by a cloud LLM.

mobile-use is used purely as a *reference for the agent architecture* — its
`Target` element-locator model, its action taxonomy, and its plan/perceive/reason/act
split. None of its transport code (ADB, uiautomator2, IDB/WDA) is reused; it is
fundamentally host-driven.

## Decisions locked during brainstorming

| Decision | Choice | Rationale |
|---|---|---|
| Control scope | **General UI control of any app** | The ambitious, high-value target; not just structured API actions. |
| Brain location | **Cloud LLM (OpenRouter)** | Reuses the existing cloud setup; frontier-model reasoning is what makes UI control reliable. Trade-off: per-step network round-trip + screen contents go to the cloud. |
| Perception | **Accessibility tree + screenshot (vision)** | mobile-use's exact approach; most robust (handles visual-only elements). Needs a vision-capable model. |
| Safety model | **Full autonomy + kill switch** | Runs the task uninterrupted; user watches and can hit an always-on-top STOP button. Fastest / most "magic". |

## Non-goals

- On-device LLM reasoning for Pilot (chosen: cloud). The dormant on-device model is not
  the Pilot brain.
- Background / unattended automation. Pilot runs **only** when explicitly invoked and
  while the user is watching.
- Replacing the Composio cloud-tools path. Pilot is a **sibling** capability for
  on-screen control; Composio remains for API-level app actions (Gmail, Slack, …).
- Game / canvas UIs that expose no accessibility nodes (known mobile-use limitation).

## Architecture

The perceive/reason/act loop is a **new sibling to `OpenRouterClient.run`**. It emits the
same `AgentEvent` stream, so the existing chat rendering, streaming, and TTS work
unchanged.

```
User: "Reply 'on my way' to Mom on WhatsApp"
        │
   ┌────▼─────────────────────────────────────────────┐
   │  PilotController  (orchestrates the loop)         │
   │  ┌──────────┐  perceive   ┌──────────────┐        │
   │  │ Perceive │────────────▶│  Cloud LLM   │        │
   │  │ a11y tree│ tree+shot    │ (OpenRouter  │        │
   │  │ +screenshot            │  vision)     │        │
   │  └──────────┘             └──────┬───────┘        │
   │        ▲                    next action           │
   │        │                         │                │
   │  ┌─────┴──────┐   actuate   ┌─────▼──────┐         │
   │  │ Accessibil-│◀────────────│  Actuator  │         │
   │  │ ityService │  gesture     │            │        │
   │  └────────────┘              └────────────┘        │
   └───────────────────────────────────────────────────┘
     loop until: done | STOP pressed | step cap | no-progress
```

### Components (all new, all on-device Kotlin)

1. **`OmniAccessibilityService`** — the eyes + hands.
   - **Perceive:** walks the active window's `AccessibilityNodeInfo` tree, flattens it to
     a JSON element list (mobile-use `Target` shape), assigning each element a stable
     integer `id` for the current step.
   - **Act:** `dispatchGesture` for tap / long-press / swipe / scroll;
     `ACTION_SET_TEXT` or an IME path for typing; global actions for back / home.
   - Hosts the floating STOP overlay (see Safety).
   - Must be a system-registered AccessibilityService — the one piece that cannot be
     anything else.

2. **`ScreenCapturer`** — MediaProjection wrapper. Grabs a compressed JPEG per step for
   the vision model. Holds the projection token for the task; releases on completion.

3. **`PilotController`** — the agent loop. Perceive → POST (tree + screenshot) to
   OpenRouter → parse one structured action → actuate → settle → repeat. Bounded by step
   cap and cancel flag. Emits `AgentEvent`s. New sibling to `OpenRouterClient.run`.

4. **`PilotOverlay`** — a `TYPE_ACCESSIBILITY_OVERLAY` floating STOP button, drawn by the
   AccessibilityService so it renders on top of every app, including full-screen ones.

### Reuse of existing code

- **`AgentEvent`** stream — Pilot emits the same events; chat UI / streaming / TTS reused.
- **`ToolHandler`-style action pattern** — the action vocabulary follows the existing
  on-device tool shape. `launch_app` reuses the dormant `LaunchAppTool`.
- **Calm-activity chat UI** (separate parked design) renders Pilot steps as one live line.

## The agent loop

One "pilot turn", repeated until `done` / STOP / step cap / no-progress:

```
1. PERCEIVE   OmniAccessibilityService.snapshotTree()  → flat JSON of on-screen elements
              ScreenCapturer.grab()                     → compressed JPEG
2. REASON     POST to OpenRouter (vision model):
                 system: pilot rules + action schema
                 user:   original task + step history
                 content:[ tree JSON, screenshot image ]
              → model returns exactly ONE structured action (tool_call)
3. ACT        Actuator dispatches the gesture via AccessibilityService
4. VERIFY     brief settle wait → loop back to PERCEIVE
```

**One action per step** (not batched): the screen changes after each act, so the model
must re-perceive. Batching causes stale-plan cascades (mobile-use lesson).

### Element shape sent to the model

```json
{ "id": 14, "text": "Message", "desc": "Message Mom",
  "resId": "com.whatsapp:id/entry", "cls": "EditText",
  "bounds": [42, 1800, 1038, 1920], "clickable": true, "editable": true }
```

Elements carry a **stable integer `id` per step**; the model refers to elements by `id`
and never emits raw coordinates. This is mobile-use's key reliability lesson — mapped
from `AccessibilityNodeInfo` fields (`viewIdResourceName`, `text`, `boundsInScreen`,
`isClickable`, `isEditable`).

### Action vocabulary (fixed, validated set)

| Action | Args | Maps to |
|---|---|---|
| `tap` | `id` | `dispatchGesture` at element center |
| `long_press` | `id` | gesture with duration |
| `type` | `id`, `text` | `ACTION_SET_TEXT` / IME |
| `swipe` | `direction`, or `from_id`→`to_id` | `dispatchGesture` path |
| `scroll` | `direction` | scroll gesture |
| `launch_app` | `package` or `name` | intent (reuses `LaunchAppTool`) |
| `back` / `home` | — | global actions |
| `wait` | `ms` | settle for dynamic UIs |
| `done` | `result` | ends loop, returns answer to chat |
| `ask` | `question` | pauses, asks user in chat (ambiguity / login wall) |

The model may emit **only** these actions; anything else is rejected locally with a
corrective message (same guardrail pattern as the Composio meta-tool fix).

## Permissions

Both are one-time user grants; Android forbids the app from self-granting either.

1. **AccessibilityService** — user enables "Omni Pilot" in
   Settings → Accessibility. Flow: in-app explainer → deep-link to the Accessibility
   settings page → detect enabled state. The explainer must state plainly, Apple-style,
   that this lets Omni **read the screen and tap for you** — no dark patterns.

2. **MediaProjection** — system consent dialog per session ("Omni will capture your
   screen"). Token held for the task duration, released on completion.

## Safety mechanics

- **Kill switch:** `TYPE_ACCESSIBILITY_OVERLAY` floating STOP button, drawn by the
  AccessibilityService so it sits above every app. Tapping it sets an **atomic cancel
  flag** the loop checks *before every act* and mid-gesture — halts within one step,
  never "one more tap after STOP".
- **Also stops on:** app backgrounded, screen off, step cap, no-progress detector.
- **Step cap (~25)** + **no-progress detector** (screenshot/tree hash unchanged after N
  acts → stop and report). Runaway-loop guard, same lesson as the Composio `MAX_HOPS`
  fix.
- **`ask` action:** on ambiguity / unexpected screen / login wall, the model pauses and
  asks in chat instead of guessing — the one place autonomy yields.
- **Visible narration:** the calm activity line shows each step live, so "watch and STOP"
  is genuinely possible.

## Privacy trade-off (stated honestly)

With cloud brain + vision, **each step sends screen contents (tree + screenshot) to
OpenRouter.** This is inherent to the chosen cloud-brain design. It will be surfaced
plainly in the explainer screen so the user makes an informed choice. Pilot runs **only**
when explicitly invoked, never in the background.

## First implementation milestone (thin vertical slice)

Before building the full action set, prove the risky path end-to-end:

1. Enable `OmniAccessibilityService` (permission flow works, enabled-state detected).
2. Perceive one screen → flatten the tree → log the element JSON.
3. Grant MediaProjection → grab one screenshot.
4. Send tree + screenshot to OpenRouter → get one `tap` action → dispatch it.
5. STOP overlay halts the loop within one step.

This validates permissions + perceive → reason → act → cancel before the full vocabulary,
narration UI, and no-progress detection are built out. Details belong to the
implementation plan.

## Open questions for the plan phase

- Exact model id: which OpenRouter vision-capable model is the Pilot default, and how the
  picker filters for `vision` support.
- Tree flattening depth / element cap to keep the payload within token budget on dense
  screens.
- Settle/verify timing between act and next perceive (fixed delay vs. wait-for-idle event).
- How the "pilot intent" is detected/triggered vs. the Composio path (explicit UI toggle
  vs. inferred from the message).
```
