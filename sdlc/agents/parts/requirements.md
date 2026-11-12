## Requirements

Based on Karl E. Wiegers and Joy Beatty, Software Requirements, 3rd edition. A requirement is one testable statement of a stakeholder need. Development writes the statement. Management keeps the id, the trace, and the status true after approval.

Specs live in `sdlc/specs/`. One file holds one requirement.

### Levels

Write the higher level before the lower level. A lower statement traces to one parent.

| Prefix | Level | States |
| --- | --- | --- |
| `BR` | Business | One measured outcome: scale, meter, target, and the stakeholder who owns the number. |
| `UR` | User | One goal of one named user class. |
| `FR` | Functional | One observable behavior of the system. |
| `QA` | Quality | Scale, meter, minimum, and target for one property of a behavior. |
| `RULE` | Business rule | A policy the organization already enforces. Functional requirements reference it by id. |
| `CON` | Constraint | A mandated limit on design or operation. |
| `IF` | Interface | One external system, one data item, one direction. |
| `DATA` | Data | One entity, its fields, and the allowed values. |

A business outcome lives on a `BR`. A user goal lives on a `UR`. An observable behavior lives on an `FR`. A mandated technology lives on a `CON`.

### File

`sdlc/specs/{folder}/{PREFIX}-{n}-{slug}.md`

| Field | Holds |
| --- | --- |
| Id | The prefix and number, such as `FR-12`. |
| Priority | `H`, `M`, or `L`, from value to the parent objective, cost, and risk. `H` is required for the parent target. `M` raises that target. `L` waits. |
| Status | `draft`, `tbd`, `approved`, `implemented`, `verified`, `deferred`, or `rejected`. |
| Trace | The parent id. `FR` traces to `UR`. `UR` traces to `BR`. `QA` traces to the behavior it measures. |
| Rationale | The outcome this statement protects, in one sentence. |
| Fit | The test that fails when the statement is unmet. |

`tbd` names the missing fact: a number, a role, or a source. An approved requirement has a fit and a parent. A rejected id stays unused.

### User goal

A user requirement names one user class and one goal. The body is the main success scenario as numbered steps. Each system step that stores data or returns data has its own functional requirement, traced to that user requirement. An alternate path and an exception path are their own functional requirements, traced to the same user requirement.

Name the user classes before the first user requirement.

### Shall

One sentence. One behavior.

`The <system> shall <observable result> when <stimulus>.`

Two behaviors are two ids.

| Open wording | Closed wording |
| --- | --- |
| support, handle, process | the stored result and the rejected input |
| fast, responsive, scalable | a meter, a load, and a percentile |
| user-friendly, intuitive, robust | the actor action and the visible outcome |
| several, a few, as appropriate, etc. | the count or the closed list |
| should, may | shall, or a separate id with priority `L` |

bad: `The system shall support fast, user-friendly search of documents and export them.`

good: `FR-12. The system shall return 20 ranked excerpts for one procurement query. Fit: on a 10 000-chunk corpus, p95 time is at most 2 seconds. Trace: UR-4.`

### Characteristics

Each requirement is necessary, unambiguous, feasible, prioritized, verifiable, and traced. The set for one feature covers the named user classes, stays consistent across ids, and stays true after one requirement changes.

A fit names the actor or the corpus, the stimulus, the expected result, and the meter.

### Scope

Scope for a release is the list of business requirements in that release. An outcome outside the release is one line: the outcome, and the business requirement it would serve.

### Change

An approved requirement changes in one edit of the shall, the fit, the trace, and the test that encodes the fit. Status moves to `implemented` when that test exists, and to `verified` when that test is green.

Code for a requirement starts after the status is `approved`.
