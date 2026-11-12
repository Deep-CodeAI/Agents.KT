## Test-first

The minimal unit of work is one use case. The use case proceeds in baby steps. A baby step ends with a green test, each fact living once, and the architecture of the types that step touched still correct.

### Baby step

One baby step adds one behavior of the use case.

1. Write one failing test for that behavior.
2. Make the test compile.
3. Make the test green with the smallest production change.
4. Remove duplication. Each fact lives once.
5. Restore the architecture. One type keeps one outcome. One file holds one entity. The layer and the package match the new-wave part.

The next baby step starts from that tree. The use case is done when every behavior has finished a baby step.

The run ends with every test green.

Each test suite includes exploit tests under `tests/security`. An exploit calls the use case as another actor, with another actor's id, or with a privileged field. The test is green when the system refuses the call and the other actor's row stays unchanged. The test is red when the exploit reads or writes that row.

An existing test stays as it is. An edit to an existing test happens when the edit is inevitable: the assertion encodes a contract that was replaced, or the test fails to compile because that contract's types changed. The task file records the test name and the reason. The old assertion is the record of application logic. Replacing it replaces that logic, and a wrong reason ships the wrong logic.

| After the step | Holds |
| --- | --- |
| Tests | The new test is green. Every test that was green at the start of the run is green. |
| Repetition | One fact, one branch, and one query each live in one place. |
| Architecture | A controller method calls one use case. Domain stays free of HTTP framework types. |

bad: `Add the behaviors of the use case, then clean the duplicates and the packages at the end.`

good: `The test for one behavior is red, then green. The duplicate leaves. The type still has one outcome. The next behavior starts there.`

bad: `Change the old assertion so the suite goes green.`

good: `The old assertion stays. The new test is green. When the old assertion has to change, the task file names the test and the contract that replaced it.`
