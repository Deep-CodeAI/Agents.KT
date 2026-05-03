package agents_engine.composition.branch

import agents_engine.core.agent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

// Tests for #880 — direct invokeSuspend coverage on Branch.
//
// Existing BranchExecutionTest / BranchSafetyTest call the synchronous
// `branch(input)` wrapper which internally does `runBlocking { invokeSuspend(input) }`.
// PIT's mutation-coverage tracking through the coroutine state-machine bytecode is
// unreliable, so those tests show every line of invokeSuspend as NO_COVERAGE.
//
// These tests call `branch.invokeSuspend(input)` directly inside a single
// `runBlocking { }` block — same execution path, but unambiguous in the
// bytecode the coverage tool actually reads.
//
// Note: the null-branch in invokeSuspend (lines 32-38) is defensive-dead-code
// not reachable through the public API. `agent<IN, OUT : Any>` requires
// non-null OUT, so a Skill cannot legitimately produce null at the type level.
// PIT correctly reports those lines as NO_COVERAGE — they exist for safety
// but no legal usage exercises them.
class BranchSuspendTest {

    sealed interface Animal
    data class Dog(val name: String) : Animal
    data class Cat(val name: String) : Animal
    data class Fish(val name: String) : Animal

    // Open hierarchy for the "no-route + no-onElse" test — validateSealedCompleteness
    // short-circuits on non-sealed source types so the runtime error path is reachable.
    open class Vehicle
    class Car : Vehicle()
    class Truck : Vehicle()
    class Bike : Vehicle()

    private fun dogHandler() = agent<Dog, String>("dog") {
        skills { skill<Dog, String>("s") { implementedBy { "dog ${it.name}" } } }
    }

    private fun catHandler() = agent<Cat, String>("cat") {
        skills { skill<Cat, String>("s") { implementedBy { "cat ${it.name}" } } }
    }

    private fun fishHandler() = agent<Fish, String>("fish") {
        skills { skill<Fish, String>("s") { implementedBy { "fish ${it.name}" } } }
    }

    private fun elseHandler() = agent<Any, String>("else") {
        skills { skill<Any, String>("s") { implementedBy { "else: ${it::class.simpleName}" } } }
    }

    @Test
    fun `invokeSuspend hits TypeRoute when result matches`() = runBlocking {
        val src = agent<String, Animal>("src") {
            skills { skill<String, Animal>("s") { implementedBy { Dog("rex") } } }
        }
        val branch = src.branch<String, Animal, String> {
            on<Dog>() then dogHandler()
            on<Cat>() then catHandler()
            on<Fish>() then fishHandler()
        }
        assertEquals("dog rex", branch.invokeSuspend("x"))
    }

    @Test
    fun `invokeSuspend picks first matching route in registration order`() = runBlocking {
        // TypeRoute matches via klass.isInstance, which covers subtypes. With
        // Animal first then Dog, Animal would match Dog. Order: more specific first.
        val src = agent<String, Animal>("src") {
            skills { skill<String, Animal>("s") { implementedBy { Cat("luna") } } }
        }
        val branch = src.branch<String, Animal, String> {
            on<Dog>() then dogHandler()
            on<Cat>() then catHandler()  // ← matches
            on<Fish>() then fishHandler()
        }
        assertEquals("cat luna", branch.invokeSuspend("x"))
    }

    @Test
    fun `invokeSuspend hits ElseRoute when no TypeRoute matches non-null result`() = runBlocking {
        // Open hierarchy so completeness check doesn't fire at construction.
        val src = agent<String, Vehicle>("src") {
            skills { skill<String, Vehicle>("s") { implementedBy { Bike() } } }
        }
        val carHandler = agent<Car, String>("c") {
            skills { skill<Car, String>("s") { implementedBy { "car" } } }
        }
        val truckHandler = agent<Truck, String>("t") {
            skills { skill<Truck, String>("s") { implementedBy { "truck" } } }
        }
        val branch = src.branch<String, Vehicle, String> {
            on<Car>() then carHandler
            on<Truck>() then truckHandler
            onElse then elseHandler()
        }
        assertEquals("else: Bike", branch.invokeSuspend("x"))
    }

    @Test
    fun `invokeSuspend errors when no route matches non-null result and no onElse declared`() = runBlocking {
        val src = agent<String, Vehicle>("src") {
            skills { skill<String, Vehicle>("s") { implementedBy { Bike() } } }
        }
        val carHandler = agent<Car, String>("c") {
            skills { skill<Car, String>("s") { implementedBy { "car" } } }
        }
        val truckHandler = agent<Truck, String>("t") {
            skills { skill<Truck, String>("s") { implementedBy { "truck" } } }
        }
        val branch = src.branch<String, Vehicle, String> {
            on<Car>() then carHandler
            on<Truck>() then truckHandler
            // no onElse — Bike will hit the post-loop error on L56
        }
        try {
            branch.invokeSuspend("x")
            fail("expected error when no route matches non-null and no onElse")
        } catch (e: IllegalStateException) {
            assertTrue(
                e.message!!.contains("Bike") || e.message!!.contains("onElse"),
                "error must name the result type or onElse: ${e.message}",
            )
        }
    }
}
