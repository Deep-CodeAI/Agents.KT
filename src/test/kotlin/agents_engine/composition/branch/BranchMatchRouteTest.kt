package agents_engine.composition.branch

import agents_engine.core.agent
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Tests for #880 / #1748 — direct coverage on Branch.matchRoute (the
// route-picking helper used by Branch.session). The existing
// BranchSessionTest exercises matchRoute at runtime, but PIT's coverage
// tracker can't see through `scope.launch { ... }` continuations, so
// every mutant in matchRoute reports as NO_COVERAGE. These tests call
// matchRoute directly on a constructed Branch, which is unambiguous in
// the bytecode PIT instruments.
//
// matchRoute precedence (mirrors invokeSuspend):
//   - null result    → first NullRoute, else first ElseRoute, else null
//   - non-null       → first TypeRoute whose klass.isInstance(result),
//                       else first ElseRoute, else null
class BranchMatchRouteTest {

    sealed interface Animal
    data class Dog(val name: String) : Animal
    data class Cat(val name: String) : Animal
    data class Fish(val name: String) : Animal

    open class Vehicle
    class Car : Vehicle()
    class Truck : Vehicle()
    class Bike : Vehicle()

    private fun stringAgent() = agent<String, String>("a") {
        skills { skill<String, String>("s") { implementedBy { it } } }
    }

    private fun handler(name: String): suspend (Any?) -> String = { "$name:$it" }

    // ── non-null result branches ──────────────────────────────────────────────

    @Test
    fun `matchRoute picks first TypeRoute whose klass isInstance result`() {
        // Kills NegateConditionalsMutator on L 103/104 (TypeRoute match check).
        val routes = listOf<BranchRoute<String>>(
            BranchRoute.TypeRoute(Dog::class, handler("dog")),
            BranchRoute.TypeRoute(Cat::class, handler("cat")),
            BranchRoute.TypeRoute(Fish::class, handler("fish")),
        )
        val branch = Branch<String, String>(source = stringAgent(), routes = routes)

        val cat = Cat("luna")
        val matched = branch.matchRoute(cat)
        assertNotNull(matched, "Cat must match the TypeRoute(Cat)")
        assertSame(routes[1], matched, "must pick the Cat route (index 1), not Dog or Fish")
    }

    @Test
    fun `matchRoute picks FIRST matching TypeRoute when subtype matches multiple klass instances`() {
        // Kills the "first matching wins" ordering — if iteration order were
        // reversed (NegateConditional inverting the for-loop continue), the
        // wrong route would fire. We register a permissive Vehicle route
        // BEFORE specific Car, then expect Vehicle (the registered-first
        // match) to win even for a Car instance.
        val vehicleRoute = BranchRoute.TypeRoute<String>(Vehicle::class, handler("vehicle"))
        val carRoute = BranchRoute.TypeRoute<String>(Car::class, handler("car"))
        val branch = Branch<String, String>(
            source = stringAgent(),
            routes = listOf(vehicleRoute, carRoute),
        )
        val matched = branch.matchRoute(Car())
        assertSame(vehicleRoute, matched,
            "first matching route wins; permissive Vehicle registered first must beat Car")
    }

    @Test
    fun `matchRoute returns null when non-null result matches no TypeRoute and no ElseRoute`() {
        // Kills NullReturnValsMutator on L 108 (`return null` at end of matchRoute).
        // Without an ElseRoute, an unmatched non-null result must produce null —
        // caller will then surface its own error.
        val branch = Branch<String, String>(
            source = stringAgent(),
            routes = listOf(
                BranchRoute.TypeRoute(Dog::class, handler("dog")),
                BranchRoute.TypeRoute(Cat::class, handler("cat")),
            ),
        )
        assertNull(branch.matchRoute(Fish("nemo")),
            "Fish doesn't match Dog/Cat and there's no ElseRoute → null")
    }

    @Test
    fun `matchRoute falls through to ElseRoute when no TypeRoute matches non-null result`() {
        // Kills NegateConditionalsMutator on the `is BranchRoute.ElseRoute ->`
        // branch of the when (L 105). Order matters: ElseRoute placed LAST.
        val typeRoute = BranchRoute.TypeRoute<String>(Dog::class, handler("dog"))
        val elseRoute = BranchRoute.ElseRoute<String>(handler("else"))
        val branch = Branch<String, String>(
            source = stringAgent(),
            routes = listOf(typeRoute, elseRoute),
        )
        val matched = branch.matchRoute(Fish("nemo"))
        assertSame(elseRoute, matched, "no TypeRoute matched Fish → ElseRoute wins")
    }

    @Test
    fun `matchRoute skips NullRoute when result is non-null even if registered first`() {
        // Kills mutants on the `is BranchRoute.NullRoute -> { /* skipped */ }`
        // when arm. A NullRoute must NOT match a non-null result.
        val nullRoute = BranchRoute.NullRoute<String>(handler("null"))
        val typeRoute = BranchRoute.TypeRoute<String>(Dog::class, handler("dog"))
        val branch = Branch<String, String>(
            source = stringAgent(),
            routes = listOf(nullRoute, typeRoute),
        )
        val matched = branch.matchRoute(Dog("rex"))
        assertSame(typeRoute, matched,
            "non-null Dog must skip NullRoute and land on the TypeRoute(Dog)")
    }

    // ── null result branches ──────────────────────────────────────────────────

    @Test
    fun `matchRoute picks first NullRoute when result is null`() {
        // Kills NegateConditionalsMutator on L 97 (`if (result == null)` boundary)
        // AND on L 98 (firstOrNull predicate for NullRoute).
        val nullRoute = BranchRoute.NullRoute<String>(handler("null"))
        val typeRoute = BranchRoute.TypeRoute<String>(Dog::class, handler("dog"))
        val elseRoute = BranchRoute.ElseRoute<String>(handler("else"))
        val branch = Branch<String, String>(
            source = stringAgent(),
            routes = listOf(typeRoute, nullRoute, elseRoute),
        )
        val matched = branch.matchRoute(null)
        assertSame(nullRoute, matched,
            "null result prefers NullRoute over ElseRoute even when registered later")
    }

    @Test
    fun `matchRoute falls back to ElseRoute when result is null and no NullRoute declared`() {
        // Kills the `?: routes.firstOrNull { it is BranchRoute.ElseRoute }`
        // Elvis fallback for null results.
        val typeRoute = BranchRoute.TypeRoute<String>(Dog::class, handler("dog"))
        val elseRoute = BranchRoute.ElseRoute<String>(handler("else"))
        val branch = Branch<String, String>(
            source = stringAgent(),
            routes = listOf(typeRoute, elseRoute),
        )
        val matched = branch.matchRoute(null)
        assertSame(elseRoute, matched,
            "null result with no NullRoute → ElseRoute fallback fires")
    }

    @Test
    fun `matchRoute returns null when result is null and neither NullRoute nor ElseRoute declared`() {
        // Kills NullReturnValsMutator on L 98 (the ?: chain's final null).
        // Without any NullRoute or ElseRoute, a null result has nowhere to go —
        // matchRoute must return null (caller surfaces the error).
        val branch = Branch<String, String>(
            source = stringAgent(),
            routes = listOf(
                BranchRoute.TypeRoute(Dog::class, handler("dog")),
                BranchRoute.TypeRoute(Cat::class, handler("cat")),
            ),
        )
        assertNull(branch.matchRoute(null),
            "null result with no NullRoute or ElseRoute → null (no error here, caller decides)")
    }

    @Test
    fun `matchRoute on empty routes list returns null for both null and non-null inputs`() {
        // Kills mutants on the for-loop entry + the early routes.firstOrNull
        // returning null on an empty list.
        val branch = Branch<String, String>(source = stringAgent(), routes = emptyList())
        assertNull(branch.matchRoute(null), "empty routes + null → null")
        assertNull(branch.matchRoute(Dog("x")), "empty routes + non-null → null")
    }

    // Note: invokeSuspend's null-result branches (L 73-80 in Branch.kt) are
    // defensive dead-code at the type level — `agent<IN, OUT : Any>` rejects
    // a null OUT, so no legal source agent can produce null. PIT correctly
    // reports those lines as NO_COVERAGE; per the convention in
    // BranchSuspendTest, we don't write tests for unreachable defensive code.
}
